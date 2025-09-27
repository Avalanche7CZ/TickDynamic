package com.wildex999.tickdynamic.listinject;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.wildex999.tickdynamic.TickDynamicMod;

import net.minecraft.world.World;

/**
 * Timed list manager for TileEntities. Minecraft 1.7.10 iterates TileEntities using index-based
 * size()/get(i) loops. To enable time-slicing and per-object timing (lag index), we must
 * implement similar logic to ListManagerEntities in size() and get().
 */
public class ListManagerTileEntities extends ListManager {

    public boolean updateStarted;
    public Iterator<EntityObject> entityIterator;
    public EntityObject lastObj;

    public CustomProfiler profiler;
    private long lastExhaustLogTick = -1L; // throttle exhausted log to once per tick

    public ListManagerTileEntities(World world, TickDynamicMod mod) {
        super(world, mod, EntityType.TileEntity);
        profiler = (CustomProfiler)world.theProfiler;
    }

    private void logExhaustedOncePerTick(String reason, int index) {
        // Reuse entity exhaust log flag for now
        if(!TickDynamicMod.entityExhaustLog) return;
        long t = world.getTotalWorldTime();
        if(t != lastExhaustLogTick) {
            System.out.println("[TickDynamic] Timed tileentity iterator " + reason + "; falling back to raw list access (index="+index+")");
            lastExhaustLogTick = t;
        }
    }

    private boolean shouldSkipSlicingForHealthyTick() {
        double thr = TickDynamicMod.entityMinSliceTickMs; // reuse threshold
        return thr > 0 && mod.lastTickDurationMs > 0 && mod.lastTickDurationMs < thr;
    }

    @Override
    public int size() {
        // Skip TE time slicing entirely if any of these conditions hold
        if(TickDynamicMod.disableTileEntityControl || shouldSkipSlicingForHealthyTick() || !mod.dynamicActive || TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing || !ListManager.isServerTickThread()) {
            updateStarted = false;
            entityIterator = null;
            return super.size();
        }
        if(profiler.stage == CustomProfiler.Stage.None || profiler.stage == CustomProfiler.Stage.InTick
                || profiler.stage == CustomProfiler.Stage.BeforeLoop || profiler.stage == CustomProfiler.Stage.InRemove)
            return super.size();

        if(!updateStarted) {
            updateStarted = true;
            entityIterator = new EntityIteratorTimed(this, this.getAge());
        }

        try {
            // Use a stable local reference to avoid NPE if the field is changed concurrently
            Iterator<EntityObject> it = this.entityIterator;
            if(it == null || !it.hasNext()) {
                updateStarted = false;
                profiler.stage = CustomProfiler.Stage.InLoop;
                return 0;
            }
        } catch(ConcurrentModificationException cme) {
            if(mod.debug)
                System.out.println("[TickDynamic] Concurrent modification detected during TE size(); ending timed iteration early.");
            updateStarted = false;
            profiler.stage = CustomProfiler.Stage.InLoop;
            return 0;
        } catch(Throwable t) {
            // Any unexpected error: stop timed iteration and fall back
            if(mod.debug)
                System.out.println("[TickDynamic] Exception in TE size(): "+t.getClass().getSimpleName()+" - "+t.getMessage());
            updateStarted = false;
            profiler.stage = CustomProfiler.Stage.InLoop;
            return 0;
        }
        return super.size();
    }

    @Override
    public EntityObject get(int index) {
        // Skip TE time slicing entirely if any of these conditions hold
        if(TickDynamicMod.disableTileEntityControl || shouldSkipSlicingForHealthyTick() || !mod.dynamicActive || TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing || !ListManager.isServerTickThread())
            return super.get(index);
        if(!updateStarted || profiler.stage == CustomProfiler.Stage.InTick)
            return super.get(index);
        // First guard: null or exhausted
        if(entityIterator == null || !entityIterator.hasNext()) {
            updateStarted = false;
            logExhaustedOncePerTick("exhausted early", index);
            int size = super.size();
            if(size == 0) return null;
            if(index >= size) index = size - 1;
            return super.get(index);
        }
        try {
            // Re-check using a stable local reference to avoid races with size()
            Iterator<EntityObject> it = entityIterator;
            if(it == null) {
                updateStarted = false;
                logExhaustedOncePerTick("iterator null", index);
                int size = super.size();
                if(size == 0) return null;
                if(index >= size) index = size - 1;
                return super.get(index);
            }
            if (!it.hasNext()) {
                updateStarted = false;
                logExhaustedOncePerTick("exhausted after re-check", index);
                int size = super.size();
                if(size == 0) return null;
                if(index >= size) index = size - 1;
                return super.get(index);
            }
            lastObj = it.next();
            return lastObj;
        } catch(NoSuchElementException nse) {
            // Iterator aborted between hasNext() and next() due to concurrent modification; gracefully fall back
            updateStarted = false;
            if(mod.debug)
                System.out.println("[TickDynamic] Timed tileentity iterator aborted; falling back to raw list access (index="+index+")");
            int size = super.size();
            if(size == 0) return null;
            if(index >= size) index = size - 1;
            return super.get(index);
        }
    }

    @Override
    public EntityObject remove(int index) {
        if(TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing || TickDynamicMod.disableTileEntityControl)
            return super.remove(index);
        if(!updateStarted || profiler.stage != CustomProfiler.Stage.InRemove)
            return super.remove(index);
        if(entityIterator == null || (!entityIterator.hasNext() && lastObj == null))
            return super.remove(index);
        entityIterator.remove();
        return lastObj;
    }

    @Override
    public Iterator<EntityObject> iterator() {
        return super.iterator();
    }
}
