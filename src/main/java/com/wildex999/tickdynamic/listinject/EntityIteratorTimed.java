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
	private long lastGtSkipLogTick = -1L;

	private static final int MAX_ITERATION_GUARD = 10000;

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
		int guard = 0;
		while (true) {
			if (guard++ > MAX_ITERATION_GUARD) {
				System.err.println("[TickDynamic][FATAL] EntityIteratorTimed.hasNext() exceeded max iterations! Breaking out to prevent server hang.");
				return false;
			}
			if (aborted || currentAge != list.age) {
				abortIteration();
				return false;
			}
			if (remainingCount > 0 && entityList != null && !entityList.isEmpty())
				return true;
			// End timer on current group if needed
			if (startedTimer && currentGroup != null) {
				currentGroup.timedGroup.endUpdateObjects(updateCount);
				currentGroup.timedGroup.endTimer();
			}
			updateCount = 0;
			currentGroup = null;
			startedTimer = false;
			entityList = null;
			cachedPlayers = null;
			nearR2 = 0;
			nearBiasActive = false;
			cachedPenMap = null;
			offenderEnabled = false;
			cachedDim = Integer.MIN_VALUE;
			cachedSkipEvery = 3;
			cachedTick = 0;
			cachedMinPenalty = 0.0;
			// Use a local variable for groupIterator to avoid repeated field access
			Iterator<EntityGroup> localGroupIterator = groupIterator;
			while (entityList == null) {
				if (localGroupIterator == null || !localGroupIterator.hasNext())
					return false;
				try {
					currentGroup = localGroupIterator.next();
				} catch (Throwable t) {
					abortIteration();
					return false;
				}
				entityList = currentGroup.entities;
				if (entityList.isEmpty()) {
					entityList = null;
					continue;
				}
				currentOffset = currentGroup.timedGroup.startUpdateObjects();
				remainingCount = currentGroup.timedGroup.getUpdateCount();
				updateCount = 0;
				// Only cache if tile entity group
				if (currentGroup.getGroupType() == EntityType.TileEntity) {
					TickDynamicMod mod = list.mod;
					if (mod != null) {
						if (mod.tileOffenderNearPlayerBias < 1.0) {
							nearBiasActive = true;
							int r = Math.max(1, mod.tileOffenderNearPlayerRadius);
							nearR2 = r * r;
							java.util.List<?> pls = (list.world instanceof net.minecraft.world.WorldServer) ? ((net.minecraft.world.WorldServer) list.world).playerEntities : list.world.playerEntities;
							cachedPlayers = (pls != null && !pls.isEmpty()) ? pls : null;
						}
						if (TickDynamicMod.tileOffenderDeprioritize && mod.server != null) {
							cachedDim = (list.world != null && list.world.provider != null) ? list.world.provider.dimensionId : Integer.MIN_VALUE;
							cachedPenMap = mod.tileOffenderPenalty.get(cachedDim);
							offenderEnabled = (cachedPenMap != null && !cachedPenMap.isEmpty());
							cachedSkipEvery = Math.max(2, TickDynamicMod.tileOffenderSkipEvery);
							cachedTick = mod.tickCounter;
							cachedMinPenalty = mod.tileOffenderSkipMinPenalty;
						}
					}
				}
			}
			return true;
		}
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
		
		// Integrate GregTech controller slowdown: skip on off ticks (configurable)
		final boolean tileGroup = (currentGroup.getGroupType() == EntityType.TileEntity);
		final boolean gtSkipEnabled = Boolean.parseBoolean(System.getProperty("tickdynamic.gt.controller.skipEnabled", "false"));
		final int gtSkipEvery = Math.max(2, Integer.getInteger("tickdynamic.gt.controller.skipEvery", 4));
		final double gtSkipActivateBelowTps = Double.parseDouble(System.getProperty("tickdynamic.gt.controller.skipActivateBelowTps", "18.0"));
		final int serverTick = (list.mod != null) ? list.mod.tickCounter : 0;
		final boolean lagging = (list.mod != null) && (list.mod.averageTPS > 0) && (list.mod.averageTPS < gtSkipActivateBelowTps);
		final boolean gtOffTick = tileGroup && gtSkipEnabled && lagging && (serverTick % gtSkipEvery) != 0;

		// Fast path: if offender system is disabled or no penalties exist, skip expensive calculations
		if(!offenderEnabled || cachedPenMap == null || cachedPenMap.isEmpty()) {
			int tries = 0, size = entityList.size();
			while(tries < size) {
				currentObject = entityList.get(currentOffset);
				currentOffset++;
				if(currentOffset >= size) currentOffset = 0;
				if(gtOffTick && currentObject != null) {
					net.minecraft.tileentity.TileEntity te = currentObject.TD_selfTileEntity;
					if(te != null && com.wildex999.tickdynamic.util.ModTileEntityUtils.isGregTechMultiblockController(te)) { tries++; logGtSkipOnce(); continue; }
				}
				break;
			}
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
        final double probCap = Math.max(0.0, Math.min(0.95, Double.parseDouble(System.getProperty("tickdynamic.tile.offender.probCap", "0.6"))));

        while(attempts < maxAttempts) {
            int idx = currentOffset;
            EntityObject cand = entityList.get(idx);
            boolean skip = false;

            try {
                net.minecraft.tileentity.TileEntity te = cand.TD_selfTileEntity;
                if(te != null) {
                    // Force skip for GT controller on off ticks
                    if(gtOffTick && com.wildex999.tickdynamic.util.ModTileEntityUtils.isGregTechMultiblockController(te)) {
                        skip = true; logGtSkipOnce();
                    } else {
                        long key = com.wildex999.tickdynamic.TickDynamicMod.packTileKey(te.xCoord, te.yCoord, te.zCoord);
                        Double pObj = cachedPenMap.get(key);
                        if(pObj != null) {
                            double p = pObj.doubleValue();
                            if(p >= cachedMinPenalty) {
                                if(!isSkipTick) {
                                    skip = false;
                                } else {
                                    long mix = key ^ tickMix;
                                    mix ^= (mix >>> 16); mix *= 0x45d9f3bL; mix ^= (mix >>> 16);
                                    double u = ((mix >>> 16) & 0xFFFF) / 65536.0;
                                    double prob = Math.min(probCap, Math.max(0, p));
                                    if(nearBiasActive && cachedPlayers != null) {
                                        boolean near = false;
                                        for(Object po : cachedPlayers) {
                                            if(!(po instanceof net.minecraft.entity.player.EntityPlayer)) continue;
                                            net.minecraft.entity.player.EntityPlayer pl = (net.minecraft.entity.player.EntityPlayer)po;
                                            int dx = ((int)pl.posX) - te.xCoord;
                                            int dy = ((int)pl.posY) - te.yCoord;
                                            int dz = ((int)pl.posZ) - te.zCoord;
                                            int d2 = dx*dx + dy*dy + dz*dz;
                                            if(d2 <= nearR2) { near = true; break; }
                                        }
                                        if(near) prob *= Math.max(0.0, list.mod.tileOffenderNearPlayerBias);
                                    }
                                    skip = (u < prob);
                                }
                            }
                        }
                    }
                }
            } catch(Throwable ignore) {
                skip = false;
            }

            if(!skip) { chosenIndex = idx; break; }
            currentOffset++; if(currentOffset >= size) currentOffset = 0; attempts++;
        }

        if(chosenIndex == -1) { chosenIndex = currentOffset; }

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

	private void logGtSkipOnce() {
		if(!com.wildex999.tickdynamic.TickDynamicMod.debug) return;
		try {
			long t = (list.world != null) ? list.world.getTotalWorldTime() : -1L;
			if(t != lastGtSkipLogTick) {
				int dim = (list.world!=null && list.world.provider!=null) ? list.world.provider.dimensionId : 0;
				System.out.println("[TickDynamic][GT] Skipping controller tick (off-tick) in DIM " + dim + ", iterator slice.");
				lastGtSkipLogTick = t;
			}
		} catch(Throwable ignore) {}
	}

}
