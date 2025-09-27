package com.wildex999.tickdynamic.listinject;

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
	
	public static final ThreadLocal<EntityObject> CURRENT = new ThreadLocal<>();

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
	// Cached per-group fields to reduce per-object overhead (tileentity groups)
	private java.util.List<?> cachedPlayers;
	private int nearR2;
	private boolean nearBiasActive;
	private java.util.concurrent.ConcurrentHashMap<Long, Double> cachedPenMap;
	private boolean offenderEnabled;
	private int cachedDim;
	private int cachedSkipEvery;
	private int cachedTick;
	private double cachedMinPenalty;

	public EntityIteratorTimed(ListManager list, int currentAge) {
		this.list = list;
		this.currentAge = currentAge;
		this.groupIterator = list.getStableGroupIterator(); // use snapshot to avoid NSE/CME
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
		cachedPlayers = null; // reset per-group cache
		nearR2 = 0;
		nearBiasActive = false;
		cachedPenMap = null;
		offenderEnabled = false;
		cachedDim = Integer.MIN_VALUE;
		cachedSkipEvery = 3;
		cachedTick = 0;
		cachedMinPenalty = 0.0;
		while(entityList == null) {
			try {
				if(groupIterator == null || !groupIterator.hasNext())
					return false;
				currentGroup = groupIterator.next();
			} catch(Throwable t) {
				// Iterator became invalid unexpectedly; abort timed iteration
				abortIteration();
				return false;
			}

			entityList = currentGroup.entities;
			if(entityList.size() <= 0)
			{
				entityList = null;
				continue;
			}
			
			currentOffset = currentGroup.timedGroup.startUpdateObjects();
			remainingCount = currentGroup.timedGroup.getUpdateCount();
			updateCount = 0;
			// Initialize caches for tileentity groups
			try {
				if(currentGroup.getGroupType() == EntityType.TileEntity) {
					TickDynamicMod mod = list.mod;
					if(mod != null) {
						// Near-player bias cache
						if(mod.tileOffenderNearPlayerBias < 1.0) {
							nearBiasActive = true;
							int r = Math.max(1, mod.tileOffenderNearPlayerRadius);
							nearR2 = r*r;
							java.util.List<?> pls = (list.world instanceof net.minecraft.world.WorldServer) ? ((net.minecraft.world.WorldServer)list.world).playerEntities : list.world.playerEntities;
							cachedPlayers = (pls != null && !pls.isEmpty()) ? pls : null;
						}
						// Offender penalty cache
						if(TickDynamicMod.tileOffenderDeprioritize && mod.server != null) {
							cachedDim = (list.world!=null && list.world.provider!=null) ? list.world.provider.dimensionId : Integer.MIN_VALUE;
							cachedPenMap = mod.tileOffenderPenalty.get(Integer.valueOf(cachedDim));
							offenderEnabled = (cachedPenMap != null && !cachedPenMap.isEmpty());
							cachedSkipEvery = Math.max(2, TickDynamicMod.tileOffenderSkipEvery);
							cachedTick = mod.tickCounter;
							cachedMinPenalty = mod.tileOffenderSkipMinPenalty;
						}
					}
				}
			} catch(Throwable ignore) {}
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
		
		// Fast path: if offender system is disabled or no penalties exist, skip expensive calculations
		if(!offenderEnabled || cachedPenMap == null || cachedPenMap.isEmpty()) {
			currentObject = entityList.get(currentOffset);
			currentOffset++;
			updateCount++;
			remainingCount--;
			CURRENT.set(currentObject);
			return currentObject;
		}

		// Optimized offender-first selection: reduce overhead
        int size = entityList.size();
        int attempts = 0;
        int maxAttempts = Math.min(size, 16); // Reduced from 64 to 16 for better performance
        int chosenIndex = -1;

        // Cache commonly used values to avoid repeated calculations
        final boolean isSkipTick = (cachedTick % cachedSkipEvery) != 0;
        final long tickMix = ((long)cachedTick) * 2654435761L; // Pre-compute part of hash mix

        while(attempts < maxAttempts) {
            int idx = currentOffset;
            EntityObject cand = entityList.get(idx);
            boolean skip = false;

            try {
                net.minecraft.tileentity.TileEntity te = cand.TD_selfTileEntity;
                if(te != null) {
                    long key = com.wildex999.tickdynamic.TickDynamicMod.packTileKey(te.xCoord, te.yCoord, te.zCoord);
                    Double pObj = cachedPenMap.get(key);
                    if(pObj != null) {
                        double p = pObj.doubleValue();
                        if(p >= cachedMinPenalty) {
                            // Guarantee every Nth tick runs to avoid starvation
                            if(!isSkipTick) {
                                skip = false; // Force run on guaranteed ticks
                            } else {
                                // Simplified pseudo-random with fewer operations
                                long mix = key ^ tickMix;
                                mix ^= (mix >>> 16); mix *= 0x45d9f3bL; mix ^= (mix >>> 16);
                                // Map to [0,1) with reduced precision for speed
                                double u = ((mix >>> 16) & 0xFFFF) / 65536.0;
                                double prob = Math.min(0.95, Math.max(0, p));

                                // Simplified near-player bias (only if active and players exist)
                                if(nearBiasActive && cachedPlayers != null) {
                                    // Quick distance check without sqrt - use squared distance
                                    boolean near = false;
                                    for(Object po : cachedPlayers) {
                                        if(!(po instanceof net.minecraft.entity.player.EntityPlayer)) continue;
                                        net.minecraft.entity.player.EntityPlayer pl = (net.minecraft.entity.player.EntityPlayer)po;
                                        int dx = ((int)pl.posX) - te.xCoord;
                                        int dy = ((int)pl.posY) - te.yCoord;
                                        int dz = ((int)pl.posZ) - te.zCoord;
                                        int d2 = dx*dx + dy*dy + dz*dz;
                                        if(d2 <= nearR2) {
                                            near = true;
                                            break;
                                        }
                                    }
                                    if(near) prob *= Math.max(0.0, list.mod.tileOffenderNearPlayerBias);
                                }

                                skip = (u < prob);
                            }
                        }
                    }
                }
            } catch(Throwable ignore) {
                // On any error, don't skip to be safe
                skip = false;
            }

            if(!skip) {
                chosenIndex = idx;
                break;
            }

            // Move to next candidate
            currentOffset++;
            if(currentOffset >= size) currentOffset = 0;
            attempts++;
        }

        // If we couldn't find a non-skipped candidate, just take the current one
        if(chosenIndex == -1) {
            chosenIndex = currentOffset;
        }

        currentObject = entityList.get(chosenIndex);
        currentOffset = chosenIndex + 1;
        updateCount++;
        remainingCount--;
        CURRENT.set(currentObject);
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
