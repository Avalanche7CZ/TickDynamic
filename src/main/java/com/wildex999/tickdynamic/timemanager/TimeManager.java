package com.wildex999.tickdynamic.timemanager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import net.minecraft.world.World;
import com.wildex999.tickdynamic.TickDynamicMod;

public class TimeManager implements ITimed {
    private int sliceMax;
    private long timeMax;
    private List<ITimed> children;
    private boolean useCached;
    private long cachedTimeUsedAverage;
    private long cachedTimeReserved;
    public final String name;
    public final TickDynamicMod mod;
    public final World world;
    public String configEntry;

    // Throttling control fields
    private static final int TICK_HISTORY_SIZE = 100; // Configurable window
    private static final long THROTTLE_THRESHOLD_MS = Long.getLong("tickdynamic.throttle.thresholdMs", 50L); // ms, throttle if tick >= this
    private static final long UNTHROTTLE_THRESHOLD_MS = Long.getLong("tickdynamic.unthrottle.thresholdMs", 40L); // ms, only unthrottle if avg < this
    private final LinkedList<Long> tickTimeHistory = new LinkedList<>();
    private boolean canUnthrottle = false;
    // Spike-resistant gating: require sustained overloads before allowing throttling
    private final int throttleWindow = Integer.getInteger("tickdynamic.throttle.window", 20);
    private final int throttleRequiredCount = Integer.getInteger("tickdynamic.throttle.requiredCount", 3);
    private boolean canThrottle = false;

    // Budget smoothing to avoid rapid oscillations
    private final java.util.HashMap<ITimed, Long> lastBudgetMap = new java.util.HashMap<ITimed, Long>();
    private final double budgetSmoothingAlpha = Double.parseDouble(System.getProperty("tickdynamic.budget.smoothingAlpha", "0.25"));
    private final double budgetMaxChangePercent = Double.parseDouble(System.getProperty("tickdynamic.budget.maxChangePercent", "0.20")); // 20%/tick

    public TimeManager(TickDynamicMod mod, World world, String name, String configEntry) {
        children = new ArrayList<>();
        if(configEntry != null)
            mod.timedObjects.put(configEntry, this);
        else
            mod.timedObjects.put(name, this);
        this.name = name;
        this.mod = mod;
        this.world = world;
        this.configEntry = configEntry;
    }

    @Override
    public void init() {
        setTimeMax(0);
        cachedTimeUsedAverage = 0;
        cachedTimeReserved = 0;
        if(configEntry != null)
            loadConfig(true);
        else
            setSliceMax(mod.defaultWorldSlicesMax);
    }

    @Override
    public String getName() { return name; }
    @Override
    public void loadConfig(boolean saveDefaults) {
        if(configEntry == null) return;
        int val = mod.config.get(configEntry, configKeySlicesMax, mod.defaultWorldSlicesMax).getInt();
        if(val == mod.defaultWorldSlicesMax) {
            try {
                if(mod.config.getCategory(configEntry).containsKey("maxSlices")) {
                    int alias = mod.config.get(configEntry, "maxSlices", val).getInt();
                    if(TickDynamicMod.debug) System.out.println("[TickDynamic][Config] Using fallback key maxSlices="+alias+" for "+configEntry);
                    val = alias;
                } else if(mod.config.getCategory(configEntry).containsKey("sliceMax")) {
                    int alias = mod.config.get(configEntry, "sliceMax", val).getInt();
                    if(TickDynamicMod.debug) System.out.println("[TickDynamic][Config] Using fallback key sliceMax="+alias+" for "+configEntry);
                    val = alias;
                }
            } catch(Exception ignore) {}
        }
        setSliceMax(val);
        if(TickDynamicMod.debug)
            System.out.println("[TickDynamic][Config] Loaded slicesMax="+getSliceMax()+" for " + configEntry);
        if(saveDefaults)
            mod.config.save();
    }
    @Override
    public void writeConfig(boolean saveFile) {
        if(configEntry == null) return;
        mod.config.get(configEntry, configKeySlicesMax, mod.defaultWorldSlicesMax).setValue(getSliceMax());
        if(saveFile) mod.config.save();
    }
    public void balanceTime() {
        if(!mod.enabled) return;
        long leftover = timeMax;
        double sumEffSlices = 0.0;
        double sumEffSlicesPrev;
        java.util.HashMap<ITimed, Double> effSlices = new java.util.HashMap<ITimed, Double>();
        // Only include non-empty groups for budgeting
        List<ITimed> childrenLeft = new ArrayList<>();
        for (ITimed timed : children) {
            boolean hasWork = true;
            int entityCount = 0;
            int tileEntityCount = 0;
            try {
                entityCount = (timed instanceof TimedEntities) ? ((TimedEntities)timed).getEntitiesCount() : 0;
            } catch (Exception ignore) {}
            try {
                tileEntityCount = timed.getTileEntitiesCount();
            } catch (Exception ignore) {}
            if (entityCount == 0 && tileEntityCount == 0) {
                hasWork = false;
            }
            if (hasWork) {
                childrenLeft.add(timed);
            } else {
                timed.setTimeMax(0);
            }
        }
        for(Iterator<ITimed> it = childrenLeft.iterator(); it.hasNext();) {
            ITimed timed = it.next();
            timed.setTimeMax(0);
            double s = timed.getSliceMax();
            if(s < 0) s = 0;
            // Penalize controllers when lagging
            if(timed instanceof TimedGroup) {
                String nm = ((TimedGroup)timed).getName();
                if(nm != null && nm.contains(".gtcontroller") && mod != null && mod.lastTickDurationMs > mod.defaultTickTime) {
                    double w = Double.parseDouble(System.getProperty("tickdynamic.controller.weightLag", "0.5"));
                    if(w < 0) w = 0; if(w > 1) w = 1;
                    s = s * w;
                }
            }
            effSlices.put(timed, Double.valueOf(s));
            sumEffSlices += s;
            if(timed.getSliceMax() == 0) {
                leftover -= timed.getTimeUsedAverage();
                it.remove();
                if(leftover <= 0) leftover = 1;
            } else {
                long reserved = timed.getReservedTime();
                leftover -= reserved;
                timed.setTimeMax(reserved);
                if(leftover <= 0) leftover = 1;
            }
        }
        sumEffSlicesPrev = sumEffSlices;
        boolean firstPass = true;
        while(leftover > 0 && childrenLeft.size() > 0) {
            long before = leftover;
            for(Iterator<ITimed> it = childrenLeft.iterator(); it.hasNext();) {
                ITimed child = it.next();
                double denom = sumEffSlices;
                long slice;
                if(denom > 0) slice = 1 + (long)Math.floor(before * ((effSlices.get(child) != null ? effSlices.get(child).doubleValue() : 0.0) / denom));
                else slice = Math.max(1L, before / Math.max(1, childrenLeft.size()));
                if(firstPass) {
                    long reserved = child.getReservedTime();
                    slice -= reserved;
                    if(slice < 0) slice = 0;
                }
                long targetMax = child.getTimeMax() + slice;
                leftover -= slice;
                long avgUsed = child.getTimeUsedAverage();
                if(avgUsed <= 0) {
                    long smoothed = applyBudgetSmoothing(child, targetMax + 1);
                    child.setTimeMax(smoothed);
                    continue;
                }
                long left = targetMax - avgUsed;
                if(left > (targetMax/100.0)) {
                    long giveBack = (long) (left-(targetMax/100.0));
                    leftover += giveBack;
                    targetMax -= giveBack;
                    it.remove();
                    sumEffSlices -= (effSlices.get(child) != null ? effSlices.get(child).doubleValue() : 0.0);
                }
                long smoothed = applyBudgetSmoothing(child, targetMax + 1);
                child.setTimeMax(smoothed);
            }
            firstPass = false;
        }
        if(leftover > 0) {
            for(ITimed child : children ) {
                double denom = sumEffSlicesPrev;
                long slice;
                if(denom > 0) slice = (long)Math.floor(leftover * ((effSlices.get(child) != null ? effSlices.get(child).doubleValue() : 0.0) / denom));
                else slice = Math.max(1L, leftover / Math.max(1, children.size()));
                long target = child.getTimeMax() + slice;
                long smoothed = applyBudgetSmoothing(child, target);
                child.setTimeMax(smoothed);
            }
        }
        for(ITimed child : children) {
            if(child.isManager())
                ((TimeManager)child).balanceTime();
        }
        for(ITimed child : children) {
            lastBudgetMap.put(child, child.getTimeMax());
        }
    }

    private long applyBudgetSmoothing(ITimed child, long target) {
        if(target < 1) target = 1;
        Long prevObj = lastBudgetMap.get(child);
        long prev = (prevObj == null) ? target : prevObj.longValue();
        // If decrease requested but throttling is not allowed (single spike), hold previous
        if(target < prev && !shouldAllowThrottle()) {
            return prev;
        }
        // Exponential smoothing
        double sm = prev + (target - prev) * Math.max(0.0, Math.min(1.0, budgetSmoothingAlpha));
        long smLong = (long)Math.max(1L, Math.round(sm));
        // Rate limit (percent of previous per tick)
        long maxDelta = (long)Math.max(1L, Math.floor(Math.abs(prev) * Math.max(0.0, budgetMaxChangePercent)));
        long delta = smLong - prev;
        if(delta > maxDelta) smLong = prev + maxDelta;
        else if(delta < -maxDelta) smLong = prev - maxDelta;
        // Gate increases if unthrottle not allowed
        if(smLong > prev && !shouldAllowUnthrottle()) {
            long softCap = prev + Math.max(1L, (long)Math.floor(prev * 0.05)); // <=5% per tick when not allowed
            if(smLong > softCap) smLong = softCap;
        }
        if(smLong < 1) smLong = 1;
        return smLong;
    }

    public void addChild(ITimed object) { children.add(object); }
    public void removeChild(ITimed object) { children.remove(object); }
    @Override
    public void setTimeMax(long newTimeMax) { timeMax = newTimeMax; }
    @Override
    public long getTimeMax() { return timeMax; }
    @Override
    public void setSliceMax(int newSliceMax) { sliceMax = newSliceMax; }
    @Override
    public int getSliceMax() { return sliceMax; }
    @Override
    public long getTimeUsed() {
        long output = 0;
        for (ITimed child : new java.util.ArrayList<>(children)) output += child.getTimeUsed();
        return output;
    }
    public long getTimeUsedAverage() {
        long output = 0;
        if (useCached) return cachedTimeUsedAverage;
        for (ITimed child : new java.util.ArrayList<>(children)) output += child.getTimeUsedAverage();
        return output;
    }
    public long getTimeUsedLast() {
        long output = 0;
        for (ITimed child : new java.util.ArrayList<>(children)) output += child.getTimeUsedLast();
        return output;
    }
    @Override
    public long getReservedTime() {
        long reservedTime = 0;
        if (useCached) return cachedTimeReserved;
        for (ITimed child : new java.util.ArrayList<>(children)) reservedTime += child.getReservedTime();
        return reservedTime;
    }
    @Override
    public void newTick(boolean recursive) {
        if (recursive) {
            for (ITimed child : new java.util.ArrayList<>(children)) child.newTick(true);
        }
        useCached = false;
    }
    public void endTick(boolean recursive) {
        if (recursive) {
            for (ITimed child : new java.util.ArrayList<>(children)) child.endTick(recursive);
        }
        cachedTimeUsedAverage = getTimeUsedAverage();
        cachedTimeReserved = getReservedTime();
    }
    @Override
    public boolean isManager() { return true; }
    public List<ITimed> getChildren() { return children; }

    /**
     * Call this at the end of each tick to record tick time and update throttling state.
     * @param tickTimeMs Tick duration in ms
     */
    public void recordTickTime(long tickTimeMs) {
        tickTimeHistory.addLast(tickTimeMs);
        if (tickTimeHistory.size() > TICK_HISTORY_SIZE) tickTimeHistory.removeFirst();
        // Compute moving average
        long sum = 0;
        for (long t : tickTimeHistory) sum += t;
        long avg = tickTimeHistory.isEmpty() ? 0 : sum / tickTimeHistory.size();
        // Only allow unthrottling if avg is well below threshold for the whole window
        canUnthrottle = (avg < UNTHROTTLE_THRESHOLD_MS && tickTimeHistory.size() == TICK_HISTORY_SIZE);
        // Compute sustained overload for throttling gate
        int window = Math.max(1, Math.min(throttleWindow, tickTimeHistory.size()));
        int countOver = 0;
        if(window > 0) {
            int i = tickTimeHistory.size() - 1;
            int considered = 0;
            while(i >= 0 && considered < window) {
                long v = tickTimeHistory.get(i);
                if(v >= THROTTLE_THRESHOLD_MS) countOver++;
                i--; considered++;
            }
        }
        canThrottle = (window >= Math.max(1, throttleRequiredCount) && countOver >= throttleRequiredCount);
    }

    /**
     * Use this to decide if a group can increase its sliceMax (unthrottle).
     * @return true if unthrottling is allowed, false otherwise
     */
    public boolean shouldAllowUnthrottle() {
        return canUnthrottle;
    }

    /**
     * Use this to decide if budgets are allowed to decrease (throttle).
     * Returns true only when we have a sustained overload in the recent window.
     */
    public boolean shouldAllowThrottle() {
        return canThrottle;
    }
}
