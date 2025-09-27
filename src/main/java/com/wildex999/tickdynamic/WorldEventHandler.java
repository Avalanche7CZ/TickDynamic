package com.wildex999.tickdynamic;

import java.util.HashMap;
import java.util.List;

import com.wildex999.tickdynamic.listinject.CustomProfiler;
import com.wildex999.tickdynamic.listinject.EntityObject;
import com.wildex999.tickdynamic.listinject.EntityType;
import com.wildex999.tickdynamic.listinject.ListManager;
import com.wildex999.tickdynamic.listinject.ListManagerEntities;
import com.wildex999.tickdynamic.listinject.ListManagerTileEntities;
import com.wildex999.tickdynamic.timemanager.ITimed;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedEntities;

import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.event.world.WorldEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.Phase;
import cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent;
import cpw.mods.fml.common.gameevent.TickEvent.WorldTickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

public class WorldEventHandler {
    public TickDynamicMod mod;

    private HashMap<World, ListManagerEntities> entityListManager;
    private HashMap<World, ListManager> tileListManager;

    public WorldEventHandler(TickDynamicMod mod) {
        this.mod = mod;
        entityListManager = new HashMap<>();
        tileListManager = new HashMap<>();
    }

    public ListManagerEntities getEntityManager(World w) { return entityListManager.get(w); }
    public ListManager getTileManager(World w) { return tileListManager.get(w); }

    @SubscribeEvent
    public void worldTickEvent(WorldTickEvent event) {
        Profiler profiler = event.world.theProfiler;
        if(!(profiler instanceof CustomProfiler)) return;
        CustomProfiler customProfiler = (CustomProfiler)profiler;

        if(event.phase == Phase.START) {
            customProfiler.setStage(CustomProfiler.Stage.InLoop);
            customProfiler.reachedTile = false;
        } else {
            customProfiler.setStage(CustomProfiler.Stage.None);
            customProfiler.reachedTile = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDimensionLoad(WorldEvent.Load event) {
        if(TickDynamicMod.isRemote(event.world)) return;

        if(mod.debug) System.out.println("World load: " + event.world.provider.getDimensionName());

        try {
            setCustomProfiler(event.world, new CustomProfiler(event.world.theProfiler, event.world));
        } catch(Exception e) {
            System.err.println("Unable to set TickDynamic World profiler! World will not be using TickDynamic: " + event.world);
            System.err.println(e);
            return;
        }

        ListManagerEntities entityManager = new ListManagerEntities(event.world, mod);
        entityListManager.put(event.world, entityManager);

        ListManager tileEntityManager = null;
        if(!TickDynamicMod.disableTileEntityControl) {
            tileEntityManager = new ListManagerTileEntities(event.world, mod);
            tileListManager.put(event.world, tileEntityManager);
        } else if(mod.debug) {
            System.out.println("[TickDynamic] TileEntity control disabled; not injecting tile list for world: " + event.world.provider.getDimensionName());
        }

        if(mod.debug) System.out.println("Adding " + event.world.loadedEntityList.size() + " existing Entities.");
        List<EntityObject> oldList = event.world.loadedEntityList;
        event.world.loadedEntityList = entityManager;
        for(EntityObject obj : oldList) { entityManager.add(obj); }

        if(!TickDynamicMod.disableTileEntityControl) {
            if(mod.debug) System.out.println("Adding " + event.world.loadedTileEntityList.size() + " existing TileEntities.");
            List<EntityObject> oldTileList = (List<EntityObject>)(List<?>)event.world.loadedTileEntityList;
            event.world.loadedTileEntityList = tileEntityManager;
            for(EntityObject obj : oldTileList) { tileEntityManager.add(obj); }
            try {
                @SuppressWarnings("unchecked")
                List<EntityObject> tickList = (List<EntityObject>)(List<?>)ReflectionHelper.getPrivateValue(World.class, event.world,
                        "tickableTileEntities",
                        "field_147484_a"
                );
                if(tickList != null && tickList != tileEntityManager) {
                    if(mod.debug) System.out.println("[TickDynamic] Swapping tickableTileEntities (found) with ListManagerTileEntities, migrating " + tickList.size() + " entries.");
                    for(EntityObject obj : tickList) { tileEntityManager.add(obj); }
                    ReflectionHelper.setPrivateValue(World.class, event.world, tileEntityManager,
                            "tickableTileEntities", "field_147484_a");
                } else if(mod.debug) {
                    System.out.println("[TickDynamic] tickableTileEntities field not found or already swapped; continuing with loadedTileEntityList only.");
                }
            } catch(Throwable t) {
                if(mod.debug) System.out.println("[TickDynamic] Could not swap tickableTileEntities list: " + t.getMessage());
            }
        }

        if(TickDynamicMod.debugGroups) {
            try { entityManager.debugDumpSummary(); } catch(Throwable ignored) {}
            if(!TickDynamicMod.disableTileEntityControl) {
                try { tileEntityManager.debugDumpSummary(); } catch(Throwable ignored) {}
                try { tileEntityManager.debugDumpTilesByBlockMeta(); } catch(Throwable ignored) {}
            }
        }

    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDimensionUnload(WorldEvent.Unload event)
    {
    	if(event.world == null || TickDynamicMod.isRemote(event.world))
    		return;
    	
    	if(mod.debug)
    		System.out.println("TickDynamic unloading injected lists for world: " + event.world.provider.getDimensionName());
    	
    	try {
    		CustomProfiler customProfiler = (CustomProfiler)event.world.theProfiler;
			setCustomProfiler(event.world, customProfiler.original);
		} catch (Exception e) {
			System.err.println("Failed to revert World Profiler to original");
			e.printStackTrace();
		}
    	
    	ListManager list = entityListManager.remove(event.world);
    	if(list != null)
    		list.clear();
    	
    	list = tileListManager.remove(event.world);
    	if(list != null)
    		list.clear();
    	
    	mod.clearWorldEntityGroups(event.world);
    	
    	ITimed manager = mod.getWorldTimeManager(event.world);
    	if(manager != null)
    		mod.timedObjects.remove(manager);
    	
    	for(ITimed timed : mod.timedObjects.values())
		{
    		if(timed instanceof TimedEntities)
    		{
    			TimedEntities timedGroup = (TimedEntities)timed;
    			if(!timedGroup.getEntityGroup().valid)
    				mod.timedObjects.remove(timedGroup);
    		}
		}
    	
    }
    
    private void setCustomProfiler(World world, Profiler profiler) throws Exception {
    	ReflectionHelper.setPrivateValue(World.class, world, profiler, "theProfiler", "field_72984_F");
    }
}
