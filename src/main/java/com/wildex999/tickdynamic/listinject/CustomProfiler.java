package com.wildex999.tickdynamic.listinject;

import com.wildex999.tickdynamic.TickDynamicMod;
import net.minecraft.profiler.Profiler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/*
 * This profiler will note where the world is while ticking Entities
 * when using a for loop for iterating.
 * This will allow us to be 100% accurate while the world is iterating, as we would know
 * whether we are ticking, or looping the list from inside a ticking entity.
 * 
 * This does however add a bit of an overhead with the function calls and String compare.
 */


public class CustomProfiler extends Profiler {

	public enum Stage {
		None, //Just pass on to original
		BeforeLoop, //World Tick has started
		InLoop, //We have started looping entities/tiles
		InTick, //We are currently ticking a single entity/tile
		InRemove //Removing dead Entity
	}
	
	public final Profiler original;
	public final net.minecraft.world.World ownerWorld; // world this profiler is attached to
	public Stage stage;
	public boolean reachedTile; //Set to true when starting to tick TileEntities
	
	// Per-object timing helpers
	private long currentObjStartNs = 0L;
	private EntityObject currentEO = null;
	// Aggregator for lag stats (per dimension)
	public static final LagIndex lagIndex = new LagIndex();

	private int depthCount; //start and end can be called inside Entity tick, we have to track it
	
	public CustomProfiler(Profiler originalProfiler, net.minecraft.world.World world) {
		this.original = originalProfiler;
		this.ownerWorld = world;
		this.stage = Stage.None;
		this.reachedTile = false;
	}

	//Set new Stage, with the ability to log the change for debugging
	public void setStage(Stage stage) {
		if(this.stage != stage && TickDynamicMod.debugProfiler) {
			System.out.println("[TickDynamic][Profiler] Stage change: " + this.stage + " -> " + stage);
		}
		this.stage = stage;
	}
	
	private static final Set<String> LOOP_START_SECTIONS = parseSystemSet("tickdynamic.profiler.loopStart", "regular,entities");
	private static final Set<String> ENTITY_TICK_SECTIONS = parseSystemSet("tickdynamic.profiler.entityTick", "tick,entityTick");
	private static final Set<String> REMOVE_SECTIONS = parseSystemSet("tickdynamic.profiler.remove", "remove");
	private static final Set<String> TILE_START_SECTIONS = parseSystemSet("tickdynamic.profiler.tileStart", "blockEntities,tileEntities");
	// New: broaden tile tick detection in various forks
	private static final Set<String> TILE_TICK_SECTIONS = parseSystemSet("tickdynamic.profiler.tileTick", "tick,update,updateTileEntity,tileEntityTick");
	private static final boolean HEURISTICS_ENABLED = Boolean.parseBoolean(System.getProperty("tickdynamic.profiler.enableHeuristics", "true"));
	private static final long MIN_RECORD_NS = Long.getLong("tickdynamic.profiler.minRecordNs", 200_000L); // 0.2 ms default

	private static Set<String> parseSystemSet(String key, String defaults) {
		String raw = System.getProperty(key, defaults);
		Set<String> set = new HashSet<String>();
		for(String part : raw.split(",")) {
			String s = part.trim();
			if(!s.isEmpty()) set.add(s);
		}
		return set;
	}

	@Override
    public void startSection(String sectionName)
    {
		if(TickDynamicMod.debugProfiler) {
			//Lightweight trace when enabled
			System.out.println("[TickDynamic][Profiler] startSection: " + sectionName + " (stage=" + stage + ")");
		}

		String lower = sectionName.toLowerCase(Locale.ROOT);
		switch(stage) {
		case None:
			break;
		case BeforeLoop:
			if(LOOP_START_SECTIONS.contains(lower)) {
				setStage(Stage.InLoop);
			} else if(HEURISTICS_ENABLED && lower.contains("entity") && !lower.contains("block") && !lower.contains("tile")) {
				// Heuristic for hybrid forks that add a differently named entity loop section
				setStage(Stage.InLoop);
			}
			break;
		case InLoop:
			if(ENTITY_TICK_SECTIONS.contains(lower) || (HEURISTICS_ENABLED && lower.contains("tick") && lower.contains("ent"))) {
				setStage(Stage.InTick);
				depthCount = 0;
				// Start per-object timing window and capture current entity/tile reference
				currentObjStartNs = System.nanoTime();
				currentEO = EntityIteratorTimed.CURRENT.get();
			} else if(REMOVE_SECTIONS.contains(lower)) {
				setStage(Stage.InRemove);
				depthCount = 0;
			} else if(TILE_START_SECTIONS.contains(lower) || (HEURISTICS_ENABLED && lower.contains("tile") && lower.contains("ent"))) {
				// Enter tile-entity phase: keep InLoop so timed TE iteration remains active
				reachedTile = true;
				// Do not change to None; stay in InLoop
			} else if(reachedTile && (TILE_TICK_SECTIONS.contains(lower) || (HEURISTICS_ENABLED && lower.contains("tick") && (lower.contains("tile") || lower.contains("block"))))) {
				// More robust TE per-object detection across forks: any tick/update inside tile phase
				setStage(Stage.InTick);
				depthCount = 0;
				currentObjStartNs = System.nanoTime();
				currentEO = EntityIteratorTimed.CURRENT.get();
			} else if(reachedTile) {
				// Heuristic: in tile-entity phase, treat the first nested section per tile as the per-object tick
				setStage(Stage.InTick);
				depthCount = 0;
				currentObjStartNs = System.nanoTime();
				currentEO = EntityIteratorTimed.CURRENT.get();
			}
			break;
		case InTick:
			//Sometimes the InTick isn't correctly closed, allow some leeway here
			if(depthCount <= 1 && REMOVE_SECTIONS.contains(lower)) {
				setStage(Stage.InRemove);
				depthCount = 0;
				break;
			}
		case InRemove:
			depthCount++;
			break;
		}

		original.startSection(sectionName);
    }
	
	@Override
	public void endSection() {
		switch(stage) {
		case InTick:
			if(depthCount-- <= 0) {
				// End of per-object timing window
				long dur = System.nanoTime() - currentObjStartNs;
				if(dur >= MIN_RECORD_NS) {
					// If we know the specific TE, record detailed stats
					if(currentEO != null && currentEO.TD_selfTileEntity != null) {
						try {
							net.minecraft.tileentity.TileEntity te = currentEO.TD_selfTileEntity;
							net.minecraft.world.World w = te.getWorldObj();
							if(w != null) lagIndex.recordTile(w.provider.dimensionId, te.xCoord, te.yCoord, te.zCoord, te, dur);
						} catch(Throwable ignored) {}
					}
				}
				// Fallback path: inside tile phase but not our timed iterator – attribute time regardless of duration
				if(currentEO == null && reachedTile && ownerWorld != null && dur > 0) {
					try {
						com.wildex999.tickdynamic.timemanager.TimedEntities grp = TickDynamicMod.tickDynamic != null ? TickDynamicMod.tickDynamic.getWorldTimedGroup(ownerWorld, "tileentity", true, true) : null;
						if(grp != null) grp.setTimeUsed(grp.getTimeUsed() + dur);
					} catch(Throwable ignored) {}
				}
				currentObjStartNs = 0L; currentEO = null;
				setStage(Stage.InLoop);
			}
			break;
		case InRemove:
			if(depthCount-- <= 0)
				setStage(Stage.InLoop);
			break;
		default:
			break;
		}
		original.endSection();
	}

	// Lightweight aggregator for top lagging TileEntities and chunk hotspots by time
	public static final class LagIndex {
		private static final int MAX_TILES = Integer.getInteger("tickdynamic.profiler.maxTiles", 2000);
		private static final int MAX_CHUNKS = Integer.getInteger("tickdynamic.profiler.maxChunks", 1000);

		private static final class TileKey { final int dim,x,y,z; TileKey(int d,int x,int y,int z){ this.dim=d; this.x=x; this.y=y; this.z=z; } @Override public int hashCode(){ int h=dim; h=31*h+x; h=31*h+y; h=31*h+z; return h; } @Override public boolean equals(Object o){ if(!(o instanceof TileKey)) return false; TileKey t=(TileKey)o; return t.dim==dim&&t.x==x&&t.y==y&&t.z==z; } }
		private static final class ChunkKey { final int dim,cx,cz; ChunkKey(int d,int cx,int cz){ this.dim=d; this.cx=cx; this.cz=cz; } @Override public int hashCode(){ int h=dim; h=31*h+cx; h=31*h+cz; return h; } @Override public boolean equals(Object o){ if(!(o instanceof ChunkKey)) return false; ChunkKey k=(ChunkKey)o; return k.dim==dim&&k.cx==cx&&k.cz==cz; } }
		public static final class TileStat { public int dim,x,y,z; public String name; public long totalNs; public int hits; }
		public static final class ChunkStat { public int dim,cx,cz; public long totalNs; public int hits; }

		private final java.util.HashMap<TileKey, TileStat> tiles = new java.util.HashMap<TileKey, TileStat>();
		private final java.util.HashMap<ChunkKey, ChunkStat> chunks = new java.util.HashMap<ChunkKey, ChunkStat>();

		public synchronized void recordTile(int dim, int x, int y, int z, net.minecraft.tileentity.TileEntity te, long ns) {
			ChunkKey ck = new ChunkKey(dim, x>>4, z>>4);
			ChunkStat cs = chunks.get(ck);
			if(cs == null) { cs = new ChunkStat(); cs.dim=dim; cs.cx=ck.cx; cs.cz=ck.cz; chunks.put(ck, cs); if(chunks.size()>MAX_CHUNKS) trimChunks(); }
			cs.totalNs += ns; cs.hits++;
			TileKey tk = new TileKey(dim,x,y,z);
			TileStat ts = tiles.get(tk);
			if(ts == null) { ts = new TileStat(); ts.dim=dim; ts.x=x; ts.y=y; ts.z=z; try { ts.name = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getNameForObject(te.getWorldObj().getBlock(x,y,z)); } catch(Throwable t) { ts.name = String.valueOf(te.getBlockType()); } tiles.put(tk, ts); if(tiles.size()>MAX_TILES) trimTiles(); }
			ts.totalNs += ns; ts.hits++;
		}

		private void trimTiles() { // drop ~25% lowest
			if(tiles.isEmpty()) return; int drop = Math.max(1, tiles.size()/4);
			java.util.List<TileStat> list = new java.util.ArrayList<TileStat>(tiles.values());
			java.util.Collections.sort(list, new java.util.Comparator<TileStat>(){ @Override public int compare(TileStat a, TileStat b){ return Long.compare(a.totalNs, b.totalNs); }});
			for(int i=0;i<drop;i++){ TileStat ts = list.get(i); tiles.remove(new TileKey(ts.dim,ts.x,ts.y,ts.z)); }
		}
		private void trimChunks() { // drop ~25% lowest
			if(chunks.isEmpty()) return; int drop = Math.max(1, chunks.size()/4);
			java.util.List<ChunkStat> list = new java.util.ArrayList<ChunkStat>(chunks.values());
			java.util.Collections.sort(list, new java.util.Comparator<ChunkStat>(){ @Override public int compare(ChunkStat a, ChunkStat b){ return Long.compare(a.totalNs, b.totalNs); }});
			for(int i=0;i<drop;i++){ ChunkStat cs = list.get(i); chunks.remove(new ChunkKey(cs.dim,cs.cx,cs.cz)); }
		}

		public synchronized java.util.List<TileStat> snapshotTopTiles(int dim, int limit) {
			java.util.ArrayList<TileStat> list = new java.util.ArrayList<TileStat>();
			for(TileStat ts : tiles.values()) if(ts.dim==dim) list.add(ts);
			java.util.Collections.sort(list, new java.util.Comparator<TileStat>(){ @Override public int compare(TileStat a, TileStat b){ return Long.compare(b.totalNs, a.totalNs); }});
			if(list.size() > limit) list.subList(limit, list.size()).clear();
			return list;
		}
		public synchronized java.util.List<ChunkStat> snapshotTopChunks(int dim, int limit) {
			java.util.ArrayList<ChunkStat> list = new java.util.ArrayList<ChunkStat>();
			for(ChunkStat cs : chunks.values()) if(cs.dim==dim) list.add(cs);
			java.util.Collections.sort(list, new java.util.Comparator<ChunkStat>(){ @Override public int compare(ChunkStat a, ChunkStat b){ return Long.compare(b.totalNs, a.totalNs); }});
			if(list.size() > limit) list.subList(limit, list.size()).clear();
			return list;
		}
		public synchronized void decay() {
			// Light decay to favor recent activity
			for(TileStat ts : tiles.values()) { ts.totalNs = (ts.totalNs*9)/10; }
			for(ChunkStat cs : chunks.values()) { cs.totalNs = (cs.totalNs*9)/10; }
		}
	}

}
