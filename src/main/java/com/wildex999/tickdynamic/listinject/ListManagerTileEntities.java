package com.wildex999.tickdynamic.listinject;

import java.util.*;

import com.wildex999.tickdynamic.TickDynamicMod;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class ListManagerTileEntities implements List<TileEntity>, java.util.RandomAccess {
    private final ListManager internalList;
    public boolean updateStarted;
    public Iterator<EntityObject> entityIterator;
    public EntityObject lastObj;
    public CustomProfiler profiler;
    private long lastExhaustLogTick = -1L;
    private final TickDynamicMod mod;
    private final World world;

    public ListManagerTileEntities(World world, TickDynamicMod mod) {
        this.world = world;
        this.mod = mod;
        this.internalList = new ListManager(world, mod, EntityType.TileEntity);
        this.profiler = (CustomProfiler) world.theProfiler;
    }

    private void logExhaustedOncePerTick(String reason, int index) {
        if(!TickDynamicMod.entityExhaustLog) return;
        long t = world.getTotalWorldTime();
        if(t != lastExhaustLogTick) {
            System.out.println("[TickDynamic] Timed tileentity iterator " + reason + "; falling back to raw list access (index="+index+")");
            lastExhaustLogTick = t;
        }
    }

    private boolean shouldSkipSlicingForHealthyTick() {
        double thr = TickDynamicMod.entityMinSliceTickMs;
        return thr > 0 && mod.lastTickDurationMs > 0 && mod.lastTickDurationMs < thr;
    }

    @Override
    public int size() {
        if (TickDynamicMod.disableTileEntityControl || shouldSkipSlicingForHealthyTick() || !mod.dynamicActive || TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing || !ListManager.isServerTickThread()) {
            updateStarted = false;
            entityIterator = null;
            return internalList.size();
        }
        if (profiler.stage == CustomProfiler.Stage.None || profiler.stage == CustomProfiler.Stage.InTick
                || profiler.stage == CustomProfiler.Stage.BeforeLoop || profiler.stage == CustomProfiler.Stage.InRemove)
            return internalList.size();
        if (!updateStarted) {
            updateStarted = true;
            entityIterator = new EntityIteratorTimed(internalList, internalList.getAge());
        }
        try {
            Iterator<EntityObject> it = this.entityIterator;
            if (it == null || !it.hasNext()) {
                updateStarted = false;
                profiler.stage = CustomProfiler.Stage.InLoop;
                return 0;
            }
        } catch (ConcurrentModificationException cme) {
            if (TickDynamicMod.debug)
                System.out.println("[TickDynamic] Concurrent modification detected during TE size(); ending timed iteration early.");
            updateStarted = false;
            profiler.stage = CustomProfiler.Stage.InLoop;
            return 0;
        } catch (Throwable t) {
            if (TickDynamicMod.debug)
                System.out.println("[TickDynamic] Exception in TE size(): " + t.getClass().getSimpleName() + " - " + t.getMessage());
            updateStarted = false;
            profiler.stage = CustomProfiler.Stage.InLoop;
            return 0;
        }
        return internalList.size();
    }

    @Override
    public boolean isEmpty() { return size() == 0; }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof TileEntity)) return false;
        for (EntityObject obj : internalList) {
            if (obj != null && obj.TD_selfTileEntity == o) return true;
        }
        return false;
    }

    @Override
    public Iterator<TileEntity> iterator() {
        final Iterator<EntityObject> it = internalList.iterator();
        return new Iterator<TileEntity>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public TileEntity next() {
                EntityObject obj = it.next();
                return (obj != null) ? obj.TD_selfTileEntity : null;
            }
            @Override public void remove() { it.remove(); }
        };
    }

    @Override
    public Object[] toArray() {
        Object[] arr = new Object[size()];
        for (int i = 0; i < size(); i++) arr[i] = get(i);
        return arr;
    }

    @Override
    public <T> T[] toArray(T[] a) {
        int sz = size();
        if (a.length < sz) a = java.util.Arrays.copyOf(a, sz);
        for (int i = 0; i < sz; i++) a[i] = (T)get(i);
        if (a.length > sz) a[sz] = null;
        return a;
    }

    public int getTotalCount() { return internalList.getTotalCount(); }

    public java.util.List<Object[]> snapshotTopTileCounts(int limit) {
        return internalList.snapshotTopTileCounts(limit);
    }

    @Override
    public boolean add(TileEntity te) {
        if (te == null) return false;
        EntityObject obj = new EntityObject();
        obj.TD_selfTileEntity = te;
        obj.TD_selfInit = true;
        return internalList.add(obj);
    }

    @Override
    public boolean remove(Object o) {
        if (!(o instanceof TileEntity)) return false;
        Iterator<EntityObject> it = internalList.iterator();
        while (it.hasNext()) {
            EntityObject obj = it.next();
            if (obj != null && obj.TD_selfTileEntity == o) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) if (!contains(o)) return false;
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends TileEntity> c) {
        boolean changed = false;
        for (TileEntity te : c) changed |= add(te);
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<? extends TileEntity> c) {
        int i = index; boolean changed = false;
        for (TileEntity te : c) { add(i++, te); changed = true; }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed = false; for (Object o : c) changed |= remove(o); return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean changed = false; Iterator<TileEntity> it = iterator(); while (it.hasNext()) { TileEntity te = it.next(); if (!c.contains(te)) { it.remove(); changed = true; } } return changed;
    }

    @Override
    public void clear() { internalList.clear(); }

    @Override
    public TileEntity get(int index) {
        if (TickDynamicMod.disableTileEntityControl || shouldSkipSlicingForHealthyTick() || !mod.dynamicActive || TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing || !ListManager.isServerTickThread())
            return unwrap(internalList.get(index));
        if (!updateStarted || profiler.stage == CustomProfiler.Stage.InTick)
            return unwrap(internalList.get(index));
        if (entityIterator == null || !entityIterator.hasNext()) {
            updateStarted = false;
            logExhaustedOncePerTick("exhausted early", index);
            int size = internalList.size();
            if (size == 0) return null;
            if (index >= size) index = size - 1;
            return unwrap(internalList.get(index));
        }
        try {
            if (!entityIterator.hasNext()) {
                updateStarted = false;
                logExhaustedOncePerTick("exhausted after re-check", index);
                int size = internalList.size();
                if (size == 0) return null;
                if (index >= size) index = size - 1;
                return unwrap(internalList.get(index));
            }
            lastObj = entityIterator.next();
            return lastObj != null ? lastObj.TD_selfTileEntity : null;
        } catch (NoSuchElementException nse) {
            updateStarted = false;
            if (TickDynamicMod.debug)
                System.out.println("[TickDynamic] Timed tile iterator aborted; falling back to raw list access (index="+index+")");
            int size = internalList.size();
            if (size == 0) return null;
            if (index >= size) index = size - 1;
            return unwrap(internalList.get(index));
        }
    }

    private TileEntity unwrap(EntityObject obj) { return (obj != null) ? obj.TD_selfTileEntity : null; }

    @Override
    public TileEntity set(int index, TileEntity element) {
        EntityObject old = internalList.get(index);
        EntityObject obj = new EntityObject(); obj.TD_selfTileEntity = element; obj.TD_selfInit = true;
        internalList.set(index, obj);
        return unwrap(old);
    }

    @Override
    public void add(int index, TileEntity element) {
        EntityObject obj = new EntityObject(); obj.TD_selfTileEntity = element; obj.TD_selfInit = true; internalList.add(index, obj);
    }

    @Override
    public TileEntity remove(int index) {
        if(TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing)
            return unwrap(internalList.remove(index));
        if(!updateStarted || profiler.stage != CustomProfiler.Stage.InRemove)
            return unwrap(internalList.remove(index));
        if(entityIterator == null || (!entityIterator.hasNext() && lastObj == null))
            return unwrap(internalList.remove(index));
        entityIterator.remove();
        return unwrap(lastObj);
    }

    @Override
    public int indexOf(Object o) {
        if (!(o instanceof TileEntity)) return -1; int i = 0; for (EntityObject obj : internalList) { if (obj != null && obj.TD_selfTileEntity == o) return i; i++; } return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        if (!(o instanceof TileEntity)) return -1; int idx = -1, i = 0; for (EntityObject obj : internalList) { if (obj != null && obj.TD_selfTileEntity == o) idx = i; i++; } return idx;
    }

    @Override
    public ListIterator<TileEntity> listIterator() { return listIterator(0); }

    @Override
    public ListIterator<TileEntity> listIterator(final int index) {
        final ListIterator<EntityObject> it = internalList.listIterator(index);
        return new ListIterator<TileEntity>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public TileEntity next() { return unwrap(it.next()); }
            @Override public boolean hasPrevious() { return it.hasPrevious(); }
            @Override public TileEntity previous() { return unwrap(it.previous()); }
            @Override public int nextIndex() { return it.nextIndex(); }
            @Override public int previousIndex() { return it.previousIndex(); }
            @Override public void remove() { it.remove(); }
            @Override public void set(TileEntity te) { EntityObject obj = new EntityObject(); obj.TD_selfTileEntity = te; obj.TD_selfInit = true; it.set(obj); }
            @Override public void add(TileEntity te) { EntityObject obj = new EntityObject(); obj.TD_selfTileEntity = te; obj.TD_selfInit = true; it.add(obj); }
        };
    }

    @Override
    public List<TileEntity> subList(int fromIndex, int toIndex) { List<EntityObject> sub = internalList.subList(fromIndex, toIndex); List<TileEntity> result = new ArrayList<>(); for (EntityObject obj : sub) { if (obj != null) result.add(obj.TD_selfTileEntity); } return result; }

    public void debugDumpSummary() { }
    public void debugDumpTilesByBlockMeta() { }

    public void tickAllTileEntities(net.minecraft.world.World world, long worldTime) {
        final int SLOW_TICK_RATE = 4;
        for (TileEntity te : this) {
            if (te == null || te.isInvalid() || te.getWorldObj() != world) continue;
            try {
                if (com.wildex999.tickdynamic.util.ModTileEntityUtils.isGregTechMultiblockController(te)) {
                    if (worldTime % SLOW_TICK_RATE == 0) te.updateEntity();
                } else te.updateEntity();
            } catch (Throwable t) {
                System.err.println("[TickDynamic] Error ticking tile entity: " + te + ", " + t);
            }
        }
    }
}
