package com.wildex999.tickdynamic.listinject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.apache.commons.lang3.NotImplementedException;

import com.wildex999.tickdynamic.TickDynamicConfig;
import com.wildex999.tickdynamic.TickDynamicMod;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.config.ConfigCategory;

/*
 * Written by: Wildex999
 * 
 * Overrides ArrayList to act as an replacement for loadedEntityList and loadedTileEntityList.
 */

public class ListManager implements List<EntityObject> {
	protected World world;
	protected TickDynamicMod mod;
	protected EntityType entityType;
	
	protected HashSet<EntityGroup> localGroups; //Groups local to the world this list is part of
	protected Map<Class<?>, EntityGroup> groupMap; //Map of Class to Group
	protected EntityGroup ungroupedEntities;
	protected Queue<EntityObject> queuedEntities; //Entities awaiting grouping, ticked as part of ungroupedEntities
	protected List<EntityPlayer> playerEntities; //List of players that should tick every server tick
	
	protected CustomProfiler customProfiler;
	
	protected int entityCount; //Real count of entities combined in all groups
	protected int age; //Used to invalidate iterators if list changes

	// Fast-path cache of the server tick thread
	public static volatile Thread SERVER_THREAD;

	// TE diagnostics (only used when entityType == TileEntity)
	private static final class BlockMeta {
		final Block block; final int meta;
		BlockMeta(Block b,int m){ this.block=b; this.meta=m; }
		@Override public boolean equals(Object o){ if(this==o) return true; if(!(o instanceof BlockMeta)) return false; BlockMeta bm=(BlockMeta)o; return meta==bm.meta && block==bm.block; }
		@Override public int hashCode(){ return System.identityHashCode(block)*31 + meta; }
	}
	private java.util.WeakHashMap<EntityObject, BlockMeta> teSnapshot; // TE -> Block/meta snapshot
	private java.util.HashMap<BlockMeta, Integer> teCounts; // Block/meta -> count

	public ListManager(World world, TickDynamicMod mod, EntityType type) {
		this.world = world;
		this.customProfiler = (CustomProfiler)world.theProfiler;
		this.mod = mod;
		this.entityType = type;
		localGroups = new HashSet<>();
		groupMap = new HashMap<>();
		playerEntities = new ArrayList<>();
		queuedEntities = new ArrayDeque<>();

		entityCount = 0;
		age = 0;
		
		if(mod.debug)
			System.out.println("Initializing " + type + " list for world: " + world.provider.getDimensionName() + "(DIM" + world.provider.dimensionId + ")");

		//Add groups from config
		loadLocalGroups();
		loadGlobalGroups();
		
		//Set default group for ungrouped
		if(type == EntityType.Entity)
            // Allow creation so entity base group always exists even if world category missing
            ungroupedEntities = mod.getWorldEntityGroup(world, "entity", type, true, false);
        else
            // Previously did not create if absent; allow creation so tileentity base group always exists
            ungroupedEntities = mod.getWorldEntityGroup(world, "tileentity", type, true, false);

        if(ungroupedEntities == null) {
            throw new RuntimeException("TickDynamic Assert failure: Could not find " + type + " group during world initialization!");
        }
        // Ensure base group is tracked in localGroups if it was auto-created without config
        if(!localGroups.contains(ungroupedEntities)) {
            localGroups.add(ungroupedEntities);
            ungroupedEntities.list = this;
        }

		createGroupMap();
		// Init TE diagnostics if relevant
		if(this.entityType == EntityType.TileEntity) {
			teSnapshot = new java.util.WeakHashMap<>();
			teCounts = new java.util.HashMap<>();
		}
	}
	
	//Add any local groups which are not already loaded
	private void loadLocalGroups() {
		//Add local groups from config
		ConfigCategory config = mod.getWorldConfigCategory(world);
		Iterator<ConfigCategory> localIt;
		for(localIt = config.getChildren().iterator(); localIt.hasNext(); )
		{
			ConfigCategory localGroupCategory = localIt.next();
			String name = localGroupCategory.getName();
			EntityGroup localGroup = mod.getWorldEntityGroup(world, name, entityType, true, true);
			if(localGroup.getGroupType() != entityType || localGroups.contains(localGroup))
				continue;

			if(mod.debug)
				System.out.println("Load local group: " + name);
			localGroups.add(localGroup);
			localGroup.list = this;
		}
	}

	//Add a copy of any global groups who are not already loaded
	private void loadGlobalGroups() {
		ConfigCategory config = mod.config.getCategory("groups");
		Iterator<ConfigCategory> globalIt;
		for(globalIt = config.getChildren().iterator(); globalIt.hasNext(); )
		{
			ConfigCategory groupCategory = globalIt.next();
			String name = groupCategory.getName();
			EntityGroup globalGroup = mod.getEntityGroup("groups." + name);

			if(globalGroup == null || globalGroup.getGroupType() != entityType)
				continue;

			//Get or create the local group as a copy of the global, but without a world config entry.
			//Will inherit config from the global group.
			EntityGroup localGroup = mod.getWorldEntityGroup(world, name, entityType, true, false);
			if(localGroups.contains(localGroup))
				continue; //Local group already defined

			if(mod.debug)
				System.out.println("Load global group: " + name);
			localGroups.add(localGroup);
			localGroup.list = this;
		}
	}
	
	//Create new Class to Group map
	public void createGroupMap() {
		if(mod.debug)
			System.out.println("Creating Group map");
		groupMap.clear();
		
		//Create map of ID to group
		for(EntityGroup group : localGroups)
		{
			Set<Class<?>> entries = group.getEntityEntries();
			for(Class<?> entityClass : entries)
			{
				if(mod.debugGroups)
				{
					String localPath = group.getConfigEntry();
					if(localPath == null)
						localPath = "-";
					String parentPath = "None";
					if(group.base != null)
						parentPath = group.base.getConfigEntry();
					System.out.println("Mapping: " + entityClass + " -> " + localPath + "(Global: " + parentPath + ")");
				}
				groupMap.put(entityClass, group);
			}
		}
		
		if(mod.debug)
			System.out.println("Done!");
	}
	
	//Re-create groups from config, and move any entities in/out due to change
	public void reloadGroups() {
		//TODO: Do partial updates each tick to not stop the world, I.e 1% of groups per tick?
		
		//Reload config, marking for removal those who no longer exists
		TickDynamicConfig.loadGroups(mod, "worlds.dim" + world.provider.dimensionId);
		
		//Move all EntityObjects to new list for later resorting into new groups
		ArrayList<EntityObject> entityList = new ArrayList<EntityObject>(entityCount);
		Iterator<EntityGroup> groupIterator = localGroups.iterator();
		while(groupIterator.hasNext()) {
			EntityGroup group = groupIterator.next();
			entityList.addAll(group.entities);
			group.clearEntities();
			
			//Group was removed from config
			if(!group.valid || (group.base != null && !group.base.valid))
				groupIterator.remove();
			else
				group.readConfig(false);
		}
		
		//Load any new groups
		loadLocalGroups();
		loadGlobalGroups();
		
		//Recreate the Group Map in case the groups have changed
		createGroupMap();
		
		//Re-sort entities into the new groups
		for(EntityObject entity : entityList) 
			assignToGroup(entity);
	}
	
	//Assign the given EntityObject to an appropriate group
	public void assignToGroup(EntityObject object) {
		if(object == null)
			return;
		EntityGroup group = object.TD_entityGroup;
		if(group != null)
			group.removeEntity(object);
		// Determine the real class to use for grouping
		Class<?> cls;
		if(object.TD_selfTileEntity != null) {
			cls = object.TD_selfTileEntity.getClass();
		} else if(object.TD_selfEntity != null) {
			cls = object.TD_selfEntity.getClass();
		} else {
			cls = object.getClass();
		}
		group = groupMap.get(cls);
		if(group == null) {
			// Try to resolve group by superclass/interface to handle subclassed Entities/TileEntities (e.g., multiblocks)
			group = resolveGroupForClass(cls);
			if(group != null) {
				// Cache the resolved mapping to avoid repeated lookups
				groupMap.put(cls, group);
			}
		}
		if(group == null)
		{
			if(mod.debugGroups)
				System.out.println("Adding Entity: " + cls + " -> Ungrouped(" + entityType + ")");
			ungroupedEntities.addEntity(object);
		}
		else
		{
			if(mod.debugGroups)
				System.out.println("Adding Entity: " + cls + " -> " + group.getName());
			group.addEntity(object);
		}
	}

	// Resolve a group for a class by checking mapped superclasses and interfaces. Picks the closest match.
	private EntityGroup resolveGroupForClass(Class<?> clazz) {
        if(clazz == null) return null;
        // Exact check first (defensive)
        EntityGroup direct = groupMap.get(clazz);
        if(direct != null) return direct;
        // Walk nearest superclass first
        for(Class<?> c = clazz.getSuperclass(); c != null; c = c.getSuperclass()) {
            EntityGroup g = groupMap.get(c);
            if(g != null) return g;
        }
        // Try any interface mapping
        for(Class<?> key : groupMap.keySet()) {
            if(key.isInterface() && key.isAssignableFrom(clazz)) {
                return groupMap.get(key);
            }
        }
        return null;
    }

	// Snapshot TE block/meta on add (after TD_Init), increment counts; skip world queries on remove
	private void teRecordAdd(EntityObject eo) {
		if(entityType != EntityType.TileEntity) return;
		try {
			TileEntity te = eo.TD_selfTileEntity;
			if(te == null) return;
			net.minecraft.world.World w = te.getWorldObj();
			if(w == null) return;
			Block b = w.getBlock(te.xCoord, te.yCoord, te.zCoord);
			int m = w.getBlockMetadata(te.xCoord, te.yCoord, te.zCoord);
			BlockMeta bm = new BlockMeta(b,m);
			if(teSnapshot == null) teSnapshot = new java.util.WeakHashMap<EntityObject, BlockMeta>();
			if(teCounts == null) teCounts = new java.util.HashMap<BlockMeta, Integer>();
			teSnapshot.put(eo, bm);
			Integer prev = teCounts.get(bm);
			teCounts.put(bm, prev==null?1:prev+1);
		} catch(Throwable ignored) {}
	}
	private void teRecordRemove(EntityObject eo) {
		if(entityType != EntityType.TileEntity) return;
		try {
			if(teSnapshot == null || teCounts == null) return;
			BlockMeta bm = teSnapshot.remove(eo);
			if(bm == null) return;
			Integer c = teCounts.get(bm);
			if(c == null || c <= 1) teCounts.remove(bm); else teCounts.put(bm, c-1);
		} catch(Throwable ignored) {}
	}

	public void debugDumpTilesByBlockMeta() {
		if(entityType != EntityType.TileEntity) return;
		try {
			System.out.println("[TickDynamic][TE Summary] World=" + (world==null?"unknown":world.provider.getDimensionName()));
			if(teCounts == null || teCounts.isEmpty()) { System.out.println("  No TileEntities counted yet."); return; }
			java.util.List<java.util.Map.Entry<BlockMeta,Integer>> list = new java.util.ArrayList<java.util.Map.Entry<BlockMeta,Integer>>(teCounts.entrySet());
			list.sort(new java.util.Comparator<java.util.Map.Entry<BlockMeta,Integer>>() { @Override public int compare(java.util.Map.Entry<BlockMeta,Integer>a, java.util.Map.Entry<BlockMeta,Integer>b){ return Integer.compare(b.getValue(), a.getValue()); } });
			int n=0; for(java.util.Map.Entry<BlockMeta,Integer> e : list) {
				BlockMeta bm = e.getKey(); int count = e.getValue();
				String name;
				try { name = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getNameForObject(bm.block); } catch(Throwable t) { name = String.valueOf(bm.block); }
				System.out.println("  " + name + ":" + bm.meta + " -> " + count);
				if(++n>=10) break;
			}
		} catch(Throwable t) {
			System.out.println("[TickDynamic][TE Summary] Error: " + t.getMessage());
		}
	}

	public int getAge() {
		return age;
	}
	
	// Lightweight diagnostics: print per-group counts and top classes across all groups
	public void debugDumpSummary() {
		try {
			java.util.HashMap<Class<?>, Integer> classCounts = new java.util.HashMap<Class<?>, Integer>();
			System.out.println("[TickDynamic][Summary] World=" + (world==null?"unknown":world.provider.getDimensionName()) + " Type=" + entityType);
			for(EntityGroup g : localGroups) {
				System.out.println("  Group " + g.getName() + ": " + g.getEntityCount());
				for(EntityObject eo : g.entities) {
					Class<?> c = eo.getClass();
					classCounts.put(c, classCounts.getOrDefault(c, 0) + 1);
				}
			}
			// Top 10 classes
			java.util.List<java.util.Map.Entry<Class<?>,Integer>> list = new java.util.ArrayList<>(classCounts.entrySet());
			list.sort(new java.util.Comparator<java.util.Map.Entry<Class<?>,Integer>>() {
				@Override public int compare(java.util.Map.Entry<Class<?>,Integer> a, java.util.Map.Entry<Class<?>,Integer> b){ return Integer.compare(b.getValue(), a.getValue()); }
			});
			int n=0; System.out.println("  Top classes:");
			for(java.util.Map.Entry<Class<?>,Integer> e : list) { System.out.println("    " + e.getKey().getName() + ": " + e.getValue()); if(++n>=10) break; }
		} catch(Throwable t) {
			System.out.println("[TickDynamic][Summary] Error while dumping summary: " + t.getMessage());
		}
	}

	//Get a new iterator for the local groups
	public Iterator<EntityGroup> getGroupIterator() {
		return localGroups.iterator();
	}

	// Return a stable snapshot iterator to avoid concurrent modification/NSE issues during timed iteration
	public Iterator<EntityGroup> getStableGroupIterator() {
		return new java.util.ArrayList<EntityGroup>(localGroups).iterator();
	}

	@Override
	public boolean add(EntityObject element) {
		if(element.TD_entityGroup != null)
            return false;
		assignToGroup(element);
		teRecordAdd(element);
		entityCount++;
		if(entityType == EntityType.TileEntity && mod != null && mod.debug) {
            System.out.println("Adding TileEntity: " + element.getClass() + " -> " + (element.TD_entityGroup != null ? element.TD_entityGroup.getName() : "Ungrouped(TileEntity)"));
        }
		return true;
	}

	@Override
	public void add(int index, EntityObject element) {
		add(element); //We ignore index(Not used in Minecraft, and doesn't make sense for us)
	}

	@Override
	public boolean addAll(Collection<? extends EntityObject> c) {
		//TODO: Actually verify that the list did change before returning true
		for(EntityObject element : c)
			add(element);
		return true;
	}

	@Override
	public boolean addAll(int index, Collection<? extends EntityObject> c) {
		return addAll(c);
	}

	@Override
	public void clear() {
		for(EntityGroup group : localGroups) {
			group.clearEntities();
		}
		entityCount = 0;
		age++;
		
		if(mod.debug)
            System.out.println("Cleared all loaded object of the type " + entityType + " from world: " + (world == null ? "Unknown" : world.provider.getDimensionName()));
		
		// clear TE diagnostics
		if(entityType == EntityType.TileEntity) {
			if(teSnapshot != null) teSnapshot.clear();
			if(teCounts != null) teCounts.clear();
		}

	}

	@Override
	public boolean contains(Object object) {
		if(!(object instanceof EntityObject)) {
			return false;
		}
		EntityObject entityObject = (EntityObject)object;
		if(entityObject.TD_entityGroup != null && entityObject.TD_entityGroup.list == this) {
			return true;
		}
		// Fallback: scan groups to determine membership
		for(EntityGroup g : localGroups) {
			if(g.entities.contains(entityObject)) return true;
		}
		return false;
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		for(Object obj : c)
		{
			if(!contains(obj))
				return false;
		}
		return true;
	}

	@Override
	public EntityObject get(int index) {
		if(index >= entityCount || index < 0)
			throw new IndexOutOfBoundsException("Tried to get index: " + index + ", but size is: " + entityCount);
		//Walk through groups, adding their size to index, until we reach the group with the index
		//Note: localGroups's order is not guaranteed to remain the same after a change.
		int offset = 0;
		for(EntityGroup group : localGroups) {
			int gc = group.getEntityCount();
			if(offset + gc > index)
				return group.entities.get(index - offset);
			offset += gc;
		}
		// If we reach here, our entityCount likely diverged from actual group sums due to concurrent changes.
		// Recalculate to self-heal and avoid crashing the server tick.
		int total = 0; for(EntityGroup g : localGroups) total += g.getEntityCount();
		entityCount = total;
		if(total <= 0) throw new IndexOutOfBoundsException("List is empty while trying to access index " + index);
		if(index >= total) index = total - 1; // clamp
		// Retry once after recomputing
		offset = 0;
		for(EntityGroup group : localGroups) {
			int gc = group.getEntityCount();
			if(offset + gc > index) return group.entities.get(index - offset);
			offset += gc;
		}
		// As a last resort return the last element of the last non-empty group
		for(EntityGroup group : localGroups) { if(!group.entities.isEmpty()) return group.entities.get(group.entities.size()-1); }
		throw new IndexOutOfBoundsException("Reached end of groups before finding index (after recompute): " + index);
	}

	@Override
	public int indexOf(Object o) {
		if(!(o instanceof EntityObject))
			return -1;
		EntityObject obj = (EntityObject)o;
		// Fast path via linkage
		if(obj.TD_entityGroup != null && obj.TD_entityGroup.list == this) {
			int offset = 0;
			for(EntityGroup group : localGroups) {
				if(obj.TD_entityGroup == group) {
					int index = group.entities.indexOf(obj);
					if(index == -1) return -1;
					return offset + index;
				}
				offset += group.getEntityCount();
			}
			return -1;
		}
		// Fallback scan
		int offset = 0;
		for(EntityGroup group : localGroups) {
			int idx = group.entities.indexOf(obj);
			if(idx != -1) return offset + idx;
			offset += group.getEntityCount();
		}
		return -1;
	}

	@Override
	public boolean isEmpty() {
		return entityCount == 0 ? true : false;
	}

	@Override
	public int lastIndexOf(Object o) {
		if(!(o instanceof EntityObject))
			return -1;
		
		EntityObject obj = (EntityObject)o;
		// Fast path via linkage
		if(obj.TD_entityGroup != null && obj.TD_entityGroup.list == this) {
			int offset = 0;
			int lastIndex = -1;
			for(EntityGroup group : localGroups) {
				if(obj.TD_entityGroup == group) {
					int index = group.entities.lastIndexOf(obj);
					if(index == -1) return -1;
					lastIndex = offset + index;
				}
				offset += group.getEntityCount();
			}
			return lastIndex;
		}
		// Fallback scan
		int offset = 0;
		int last = -1;
		for(EntityGroup group : localGroups) {
			int idx = group.entities.lastIndexOf(obj);
			if(idx != -1) last = offset + idx;
			offset += group.getEntityCount();
		}
		return last;
	}
	
	@Override
	public Iterator<EntityObject> iterator() {
		// Only the TileEntity list should react to the tile-phase flag.
		if(this.entityType == EntityType.TileEntity && customProfiler != null && customProfiler.reachedTile) {
			customProfiler.reachedTile = false; // consume the flag for TE list only
			return new EntityIteratorTimed(this, getAge());
		}
		return new EntityIterator(this, getAge());
	}

	@Override
	public ListIterator<EntityObject> listIterator() {
		throw new NotImplementedException("listIterator is not implemented in TickDynamic's List implementation!");
	}

	@Override
	public ListIterator<EntityObject> listIterator(int index) {
		throw new NotImplementedException("listIterator(index) is not implemented in TickDynamic's List implementation!");
	}

	@Override
	public boolean remove(Object object) {
		if(!(object instanceof EntityObject)) return false;
		EntityObject entityObject = (EntityObject)object;
		EntityGroup grp = entityObject.TD_entityGroup;
		if(grp == null || grp.list != this) {
			// Fallback: locate the group that currently contains this object
			for(EntityGroup g : localGroups) {
				if(g.entities.contains(entityObject)) { grp = g; break; }
			}
		}
		if(grp == null) return false;
		if(grp.removeEntity(entityObject)) {
			entityCount--; age++; teRecordRemove(entityObject); return true;
		}
		return false;
	}

	@Override
	public EntityObject remove(int index) {
		if(mod.debug)
		{
			Thread.currentThread().dumpStack();
			System.out.println("Debug Warning: Using slow remove of objects(Remove by index)!");
		}
		EntityObject obj = get(index);
		if(remove(obj))
			return obj;
		return null;
	}

	@Override
	public Object[] toArray() {
        Object[] arr = new Object[entityCount];
        int i=0;
        for(EntityGroup g : localGroups) {
            for(EntityObject eo : g.entities) arr[i++] = eo;
        }
        return arr;
    }

    @Override
    public <T> T[] toArray(T[] a) {
        int size = entityCount;
        if(a.length < size) {
            @SuppressWarnings("unchecked")
            T[] newA = (T[])java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
            a = newA;
        }
        int i=0; for(EntityGroup g: localGroups) for(EntityObject eo: g.entities) { @SuppressWarnings("unchecked") T t=(T)eo; a[i++]=t; }
        if(a.length > size) a[size]=null;
        return a;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed=false;
        for(Object o: c) changed |= remove(o);
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        // Build set of objects to retain
        java.util.HashSet<Object> keep = new java.util.HashSet<Object>(c);
        boolean changed=false;
        for(EntityGroup g : localGroups) {
            for(Iterator<EntityObject> it = g.entities.iterator(); it.hasNext();) {
                EntityObject eo = it.next();
                if(!keep.contains(eo)) { eo.TD_Deinit(); it.remove(); entityCount--; changed=true; }
            }
        }
        if(changed) age++;
        return changed;
    }

    @Override
    public int size() { return entityCount; }

    // Public diagnostic helper for TileEntities: snapshot top counts by Block registry name and meta
    public java.util.List<Object[]> snapshotTopTileCounts(int limit) {
		java.util.ArrayList<Object[]> out = new java.util.ArrayList<Object[]>();
		if(entityType != EntityType.TileEntity) return out;
		try {
			if(teCounts == null || teCounts.isEmpty()) return out;
			java.util.List<java.util.Map.Entry<BlockMeta,Integer>> list = new java.util.ArrayList<java.util.Map.Entry<BlockMeta,Integer>>(teCounts.entrySet());
			list.sort(new java.util.Comparator<java.util.Map.Entry<BlockMeta,Integer>>() { @Override public int compare(java.util.Map.Entry<BlockMeta,Integer>a, java.util.Map.Entry<BlockMeta,Integer>b){ return Integer.compare(b.getValue(), a.getValue()); } });
			int n=0; for(java.util.Map.Entry<BlockMeta,Integer> e : list) {
				BlockMeta bm = e.getKey(); int count = e.getValue();
				String name;
				try { name = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getNameForObject(bm.block); } catch(Throwable t) { name = String.valueOf(bm.block); }
				out.add(new Object[]{ name, Integer.valueOf(bm.meta), Integer.valueOf(count) });
				if(++n >= Math.max(1, limit)) break;
			}
		} catch(Throwable ignored) {}
		return out;
	}

	@Override
	public EntityObject set(int index, EntityObject element) { throw new UnsupportedOperationException("set not supported"); }

	@Override
	public List<EntityObject> subList(int fromIndex, int toIndex) { throw new UnsupportedOperationException("subList not supported"); }

	public static boolean isServerTickThread() {
        Thread t = Thread.currentThread();
        Thread st = SERVER_THREAD;
        try {
            // If cached thread matches, accept immediately
            if(st != null && t == st) return true;
            // Fall back to name heuristic regardless of cache state
            String n = t.getName();
            if(n == null) return true;
            if(n.equals("Server thread") || n.startsWith("Server thread") || n.equals("Server") || n.startsWith("Server")) return true;
            return false;
        } catch(Throwable ignored) { return true; }
    }

    public int getTotalCount() { return entityCount; }
}
