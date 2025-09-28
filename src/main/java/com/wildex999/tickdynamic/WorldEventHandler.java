package com.wildex999.tickdynamic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.wildex999.tickdynamic.listinject.CustomProfiler;
import com.wildex999.tickdynamic.listinject.EntityObject;
import com.wildex999.tickdynamic.listinject.ListManagerEntities;
import com.wildex999.tickdynamic.listinject.ListManagerTileEntities;
import com.wildex999.tickdynamic.listinject.TrackingList;
import com.wildex999.tickdynamic.timemanager.ITimed;
import com.wildex999.tickdynamic.timemanager.TimedEntities;

import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.Phase;
import cpw.mods.fml.common.gameevent.TickEvent.WorldTickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

public class WorldEventHandler {
    public TickDynamicMod mod;

    private HashMap<World, ListManagerEntities> entityListManager;
    private HashMap<World, ListManagerTileEntities> tileListManager;

    public WorldEventHandler(TickDynamicMod mod) {
        this.mod = mod;
        entityListManager = new HashMap<>();
        tileListManager = new HashMap<>();
    }

    public ListManagerEntities getEntityManager(World w) { return entityListManager.get(w); }
    public ListManagerTileEntities getTileManager(World w) { return tileListManager.get(w); }

    @SubscribeEvent
    public void worldTickEvent(cpw.mods.fml.common.gameevent.TickEvent.WorldTickEvent event) {
        Profiler profiler = event.world.theProfiler;
        if(!(profiler instanceof CustomProfiler)) return;
        CustomProfiler customProfiler = (CustomProfiler)profiler;

        if(event.phase == Phase.START) {
            customProfiler.setStage(CustomProfiler.Stage.InLoop);
            customProfiler.reachedTile = false;
        } else {
            // Before leaving the tick, flush any pending manual TE timing
            try { customProfiler.manualFlush(); } catch(Throwable ignore) {}
            customProfiler.setStage(CustomProfiler.Stage.None);
            customProfiler.reachedTile = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDimensionLoad(WorldEvent.Load event) {
        if(TickDynamicMod.isRemote(event.world)) return;

        if(TickDynamicMod.debug) System.out.println("World load: " + event.world.provider.getDimensionName());

        try {
            setCustomProfiler(event.world, new CustomProfiler(event.world.theProfiler, event.world));
        } catch(Exception e) {
            System.err.println("Unable to set TickDynamic World profiler! World will not be using TickDynamic: " + event.world);
            e.printStackTrace();
            return;
        }

        // Wrap tickableTileEntities with a tracking view so we can capture the TE being processed
        try {
            List<?> tickList = (List<?>)ReflectionHelper.getPrivateValue(World.class, event.world,
                    "tickableTileEntities",
                    "field_147484_a"
            );
            if(tickList != null && !(tickList instanceof TrackingList)) {
                @SuppressWarnings("unchecked")
                List<Object> base = (List<Object>) tickList;
                TrackingList<Object> tracking = new TrackingList<Object>(base, (CustomProfiler)event.world.theProfiler);
                ReflectionHelper.setPrivateValue(World.class, event.world, tracking,
                        "tickableTileEntities", "field_147484_a");
                if(TickDynamicMod.debug) System.out.println("[TickDynamic] Wrapped tickableTileEntities with TrackingList for dim " + event.world.provider.dimensionId);
            }
        } catch(Throwable t) {
            if(TickDynamicMod.debug) System.out.println("[TickDynamic] Could not wrap tickableTileEntities with TrackingList: " + t.getMessage());
        }

        // Also wrap loadedTileEntityList to cover mods that iterate this list directly
        try {
            List<?> loaded = event.world.loadedTileEntityList;
            if(loaded != null && !(loaded instanceof TrackingList)) {
                @SuppressWarnings("unchecked")
                List<Object> base = (List<Object>) loaded;
                TrackingList<Object> trackingLoaded = new TrackingList<Object>(base, (CustomProfiler)event.world.theProfiler);
                event.world.loadedTileEntityList = trackingLoaded;
                if(TickDynamicMod.debug) System.out.println("[TickDynamic] Wrapped loadedTileEntityList with TrackingList for dim " + event.world.provider.dimensionId);
            }
        } catch(Throwable t) {
            if(TickDynamicMod.debug) System.out.println("[TickDynamic] Could not wrap loadedTileEntityList with TrackingList: " + t.getMessage());
        }

        ListManagerEntities entityManager = new ListManagerEntities(event.world, mod);
        entityListManager.put(event.world, entityManager);

        ListManagerTileEntities tileEntityManager = null;
        if(!TickDynamicMod.disableTileEntityControl) {
            tileEntityManager = new ListManagerTileEntities(event.world, mod);
            tileListManager.put(event.world, tileEntityManager);
        } else if(TickDynamicMod.debug) {
            System.out.println("[TickDynamic] TileEntity control disabled; not injecting tile list for world: " + event.world.provider.getDimensionName());
        }

        if(TickDynamicMod.debug) System.out.println("Adding " + event.world.loadedEntityList.size() + " existing Entities.");
        List<EntityObject> oldList = event.world.loadedEntityList;
        event.world.loadedEntityList = entityManager;
        for(EntityObject obj : oldList) { entityManager.add(obj); }

        // TE injection: enable swapping tickableTileEntities only when explicitly opted-in (configurable via -Dtickdynamic.tile.swapTickableList=true)
        boolean swapTickable = Boolean.parseBoolean(System.getProperty("tickdynamic.tile.swapTickableList", "false"));
        // Optional dimension whitelist for TE injection
        String dimListProp = System.getProperty("tickdynamic.tile.injectDims", "").trim();
        java.util.HashSet<Integer> dimWhitelist = null;
        if(!dimListProp.isEmpty()) {
            dimWhitelist = new java.util.HashSet<Integer>();
            for(String part : dimListProp.split(",")) {
                try { dimWhitelist.add(Integer.parseInt(part.trim())); } catch(Throwable ignore) {}
            }
        }
        boolean dimAllowed = (dimWhitelist == null) || dimWhitelist.contains(event.world.provider.dimensionId);
        if(!TickDynamicMod.disableTileEntityControl && swapTickable && dimAllowed) {
            if(TickDynamicMod.debug) System.out.println("Adding " + event.world.loadedTileEntityList.size() + " existing TileEntities.");
            List<?> oldTileList = event.world.loadedTileEntityList;
            event.world.loadedTileEntityList = tileEntityManager;
            java.util.Set<net.minecraft.tileentity.TileEntity> added = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<net.minecraft.tileentity.TileEntity, Boolean>());
            for(Object obj : oldTileList) {
                net.minecraft.tileentity.TileEntity te = null;
                if(obj instanceof net.minecraft.tileentity.TileEntity) te = (net.minecraft.tileentity.TileEntity)obj;
                else if(obj instanceof EntityObject) {
                    EntityObject eo = (EntityObject)obj; te = eo.TD_selfTileEntity;
                }
                if(te != null && added.add(te)) {
                    if(tileEntityManager != null) tileEntityManager.add(te);
                }
            }
            try {
                List<?> tickList = (List<?>)ReflectionHelper.getPrivateValue(World.class, event.world,
                        "tickableTileEntities",
                        "field_147484_a"
                );
                if(tickList != null && tickList != tileEntityManager) {
                    if(TickDynamicMod.debug) System.out.println("[TickDynamic] Swapping tickableTileEntities (found) with ListManagerTileEntities, migrating " + tickList.size() + " entries.");
                    for(Object obj : tickList) {
                        net.minecraft.tileentity.TileEntity te = null;
                        if(obj instanceof net.minecraft.tileentity.TileEntity) te = (net.minecraft.tileentity.TileEntity)obj;
                        else if(obj instanceof EntityObject) { EntityObject eo = (EntityObject)obj; te = eo.TD_selfTileEntity; }
                        if(te != null && added.add(te)) {
                            if(tileEntityManager != null) tileEntityManager.add(te);
                        }
                    }
                    ReflectionHelper.setPrivateValue(World.class, event.world, tileEntityManager,
                            "tickableTileEntities", "field_147484_a");
                } else if(TickDynamicMod.debug) {
                    System.out.println("[TickDynamic] tickableTileEntities field not found or already swapped; leaving lists as-is.");
                }
            } catch(Throwable t) {
                if(TickDynamicMod.debug) System.out.println("[TickDynamic] Could not swap tickableTileEntities list: " + t.getMessage());
            }
        } else if(!TickDynamicMod.disableTileEntityControl && TickDynamicMod.debug) {
            if(!dimAllowed) System.out.println("[TickDynamic] TE injection skipped for DIM " + event.world.provider.dimensionId + " (not in tickdynamic.tile.injectDims)");
            else System.out.println("[TickDynamic] TE injection disabled or swapTickable=false; leaving original lists intact for world: " + event.world.provider.getDimensionName());
        }

        if(TickDynamicMod.debugGroups) {
            try { entityManager.debugDumpSummary(); } catch(Throwable ignored) {}
            if(!TickDynamicMod.disableTileEntityControl) {
                try { if(tileEntityManager != null) tileEntityManager.debugDumpSummary(); } catch(Throwable ignored) {}
                try { if(tileEntityManager != null) tileEntityManager.debugDumpTilesByBlockMeta(); } catch(Throwable ignored) {}
            }
        }

    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDimensionUnload(WorldEvent.Unload event)
    {
    	if(event.world == null || TickDynamicMod.isRemote(event.world))
    		return;
    	
    	if(TickDynamicMod.debug)
    		System.out.println("TickDynamic unloading injected lists for world: " + event.world.provider.getDimensionName());
    	
    	try {
    		CustomProfiler customProfiler = (CustomProfiler)event.world.theProfiler;
			setCustomProfiler(event.world, customProfiler.original);
		} catch (Exception e) {
			System.err.println("Failed to revert World Profiler to original");
			if(TickDynamicMod.debug) e.printStackTrace();
		}
    	
    	ListManagerEntities entityList = entityListManager.remove(event.world);
    	if(entityList != null)
    		entityList.clear();

    	ListManagerTileEntities tileList = tileListManager.remove(event.world);
    	if(tileList != null)
    		tileList.clear();

    	mod.clearWorldEntityGroups(event.world);
    	
    	ITimed manager = mod.getWorldTimeManager(event.world);
    	if(manager != null)
    		mod.timedObjects.values().remove(manager);

    	for(ITimed timed : mod.timedObjects.values())
		{
    		if(timed instanceof TimedEntities)
    		{
    			TimedEntities timedGroup = (TimedEntities)timed;
    			if(!timedGroup.getEntityGroup().valid)
    				mod.timedObjects.values().remove(timedGroup);
    		}
		}
    }
    
    private void setCustomProfiler(World world, Profiler profiler) {
    	ReflectionHelper.setPrivateValue(World.class, world, profiler, "theProfiler", "field_72984_F");
    }
}
