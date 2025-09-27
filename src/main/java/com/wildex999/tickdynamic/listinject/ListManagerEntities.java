package com.wildex999.tickdynamic.listinject;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.wildex999.tickdynamic.TickDynamicMod;

import net.minecraft.world.World;

public class ListManagerEntities extends ListManager {

	public boolean updateStarted;
	public Iterator<EntityObject> entityIterator;
	public EntityObject lastObj;
	
	public CustomProfiler profiler;
	private long lastExhaustLogTick = -1L; // throttle exhausted log to once per tick

	public ListManagerEntities(World world, TickDynamicMod mod) {
		super(world, mod, EntityType.Entity);
		profiler = (CustomProfiler)world.theProfiler;
	}
	
	private void logExhaustedOncePerTick(String reason, int index) {
		if(!TickDynamicMod.entityExhaustLog) return;
		long t = world.getTotalWorldTime();
		if(t != lastExhaustLogTick) {
			System.out.println("[TickDynamic] Timed entity iterator " + reason + "; falling back to raw list access (index="+index+")");
			lastExhaustLogTick = t;
		}
	}

	private boolean shouldSkipSlicingForHealthyTick() {
		double thr = TickDynamicMod.entityMinSliceTickMs;
		return thr > 0 && mod.lastTickDurationMs > 0 && mod.lastTickDurationMs < thr;
	}

	@Override
	public int size() {
		// Skip entity time slicing entirely if any of these conditions hold
		if(shouldSkipSlicingForHealthyTick() || !mod.dynamicActive || TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing || !ListManager.isServerTickThread()) {
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
			if(entityIterator == null || !entityIterator.hasNext()) {
				updateStarted = false;
				profiler.stage = CustomProfiler.Stage.InLoop;
				return 0;
			}
		} catch(ConcurrentModificationException cme) {
			if(mod.debug)
				System.out.println("[TickDynamic] Concurrent modification detected during size(); ending timed iteration early.");
			updateStarted = false;
			profiler.stage = CustomProfiler.Stage.InLoop;
			return 0;
		}
		return super.size();
	}
	
	@Override
	public EntityObject get(int index) {
		// Skip entity time slicing entirely if any of these conditions hold
		if(shouldSkipSlicingForHealthyTick() || !mod.dynamicActive || TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing || !ListManager.isServerTickThread())
			return super.get(index);
		if(!updateStarted || profiler.stage == CustomProfiler.Stage.InTick)
			return super.get(index);
		if(entityIterator == null || !entityIterator.hasNext()) {
			updateStarted = false;
			logExhaustedOncePerTick("exhausted early", index);
			int size = super.size();
			if(size == 0) return null;
			if(index >= size) index = size - 1;
			return super.get(index);
		}
		try {
			// Re-check hasNext() to defend against concurrent modification between the check above and this call.
			if (!entityIterator.hasNext()) {
				updateStarted = false;
				logExhaustedOncePerTick("exhausted after re-check", index);
				int size = super.size();
				if(size == 0) return null;
				if(index >= size) index = size - 1;
				return super.get(index);
			}
			lastObj = entityIterator.next();
			return lastObj;
		} catch(NoSuchElementException nse) {
			// Iterator aborted between hasNext() and next() due to concurrent modification; gracefully fall back
			updateStarted = false;
			if(mod.debug)
				System.out.println("[TickDynamic] Timed entity iterator aborted; falling back to raw list access (index="+index+")");
			int size = super.size();
			if(size == 0) return null;
			if(index >= size) index = size - 1;
			return super.get(index);
		}
	}
	
	@Override
	public EntityObject remove(int index) {
		if(TickDynamicMod.safeEntityIteration || TickDynamicMod.disableEntityTimeSlicing)
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
