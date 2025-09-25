package com.wildex999.tickdynamic.listinject;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.wildex999.tickdynamic.TickDynamicMod;

/*
 * Iterator will continue from each group at the given offset and only iterate for the given number
 * of Entities as dictated by the time manager.
 * It will also take care of timing each group.
 */

public class EntityIteratorTimed implements Iterator<EntityObject> {
	
	private ListManager list;
	private int currentAge; //Used to verify if iterator is still valid(Concurrent modification)
	
	private int remainingCount; //Number of entities left to update for this group
	private int currentOffset; //Offset in current entity list
	private int updateCount;
	private boolean startedTimer;
	private boolean aborted; // Set true if we abort due to concurrent modification

	private EntityGroup currentGroup;
	private EntityObject currentObject;
	private Iterator<EntityGroup> groupIterator;
	private List<EntityObject> entityList;
	
	public EntityIteratorTimed(ListManager list, int currentAge) {
		this.list = list;
		this.currentAge = currentAge;
		this.groupIterator = list.getGroupIterator();
		this.remainingCount = 0;
		this.startedTimer = false;
		this.aborted = false;
	}
	
	@Override
	public boolean hasNext() {
		if(aborted)
			return false;
		if(currentAge != list.age) {
			// Instead of throwing CME, gracefully abort iteration
			abortIteration();
			return false;
		}
		if(remainingCount > 0 && entityList != null && !entityList.isEmpty())
			return true;
		
		//Find next group and end timer on current group
		if(startedTimer && currentGroup != null)
		{
			currentGroup.timedGroup.endUpdateObjects(updateCount);
			currentGroup.timedGroup.endTimer();
		}
		
		updateCount = 0;
		currentGroup = null;
		startedTimer = false;
		entityList = null;
		while(entityList == null) {
			if(!groupIterator.hasNext())
				return false;
			currentGroup = groupIterator.next();
			
			entityList = currentGroup.entities;
			if(entityList.size() <= 0)
			{
				entityList = null;
				continue;
			}
			
			currentOffset = currentGroup.timedGroup.startUpdateObjects();
			remainingCount = currentGroup.timedGroup.getUpdateCount();
			updateCount = 0;
		}
		
		return true;
	}

	private void abortIteration() {
		if(!aborted) {
			aborted = true;
			if(startedTimer && currentGroup != null) {
				try {
					currentGroup.timedGroup.endUpdateObjects(updateCount);
					currentGroup.timedGroup.endTimer();
				} catch(Throwable ignored) {}
			}
			if(TickDynamicMod.debug)
				System.out.println("[TickDynamic] Aborting timed entity iteration due to concurrent modification.");
			TickDynamicMod.entityFallbackEvents++;
			if(!TickDynamicMod.autoEntitySafeTriggered && TickDynamicMod.entityFallbackEvents >= TickDynamicMod.autoSafeEntityThreshold) {
				TickDynamicMod.autoEntitySafeTriggered = true;
				TickDynamicMod.safeEntityIteration = true;
				System.out.println("[TickDynamic] Auto-enabled safe entity iteration mode after " + TickDynamicMod.entityFallbackEvents + " fallback events.");
			}
		}
	}

	@Override
	public EntityObject next() {
		if(aborted)
			throw new NoSuchElementException();
		if(currentAge != list.age) {
			abortIteration();
			throw new NoSuchElementException();
		}
		if(!hasNext()) //hasNext will also setup next group if necessary(Usually called before next anyway)
			throw new NoSuchElementException();
		
		if(!startedTimer) {
			startedTimer = true;
			currentGroup.timedGroup.startTimer();
		}
		
		if(currentOffset >= entityList.size()) { //Loop around
			currentOffset = 0;
		}
		
		currentObject = entityList.get(currentOffset);
		remainingCount--;
		currentOffset++;
		updateCount++;
		
		return currentObject;
	}
	
	@Override
	public void remove() {
		if(aborted)
			return;
		if(currentAge != list.age) {
			abortIteration();
			return;
		}
		if(currentObject == null)
			return;
		
		if(list.remove(currentObject)) {
			currentAge++; // stay in sync but won't abort now since we incremented our view
			currentOffset--;
		} else if(TickDynamicMod.debug) {
			System.err.println("[TickDynamic] Failed iterator remove for object: " + currentObject);
		}

		if(currentOffset < 0)
			currentOffset = 0;
	}

}
