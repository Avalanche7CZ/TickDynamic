package com.wildex999.tickdynamic.timemanager;

import java.util.Iterator;
import java.util.LinkedList;
import net.minecraft.world.World;
import com.wildex999.tickdynamic.TickDynamicMod;

public class TimedGroup implements ITimed {
    protected int sliceMax;
    protected long timeMax;
    protected long timeUsed;
    protected long timeUsedAverage;
    protected long prevTimeUsed;
    protected int objectsRun;
    protected int objectsRunAverage;
    protected int prevObjectsRun;
    protected long startTime;
    protected int averageTicks = 20;
    protected LinkedList<Long> listTimeUsed;
    protected LinkedList<Integer> listObjectsRun;
    public final String name;
    public final TickDynamicMod mod;
    public final World world;
    public String configEntry;
    public enum GroupType { TileEntity, Entity, Other }
    protected int targetSliceMax;
    protected int rampRate = 10; // Max change per tick, configurable
    protected com.wildex999.tickdynamic.listinject.ListManagerTileEntities tileEntityListManager;

    public TimedGroup(TickDynamicMod mod, World world, String name, String configEntry) {
        this(mod, world, name, configEntry, null);
    }

    public TimedGroup(TickDynamicMod mod, World world, String name, String configEntry, com.wildex999.tickdynamic.listinject.ListManagerTileEntities tileEntityListManager) {
        if(configEntry != null)
            mod.timedObjects.put(configEntry, this);
        else
            mod.timedObjects.put(name, this);
        this.name = name;
        this.mod = mod;
        this.world = world;
        this.configEntry = configEntry;
        this.tileEntityListManager = tileEntityListManager;
        listTimeUsed = new LinkedList<>();
        listObjectsRun = new LinkedList<>();
    }

    @Override
    public void init() {
        timeUsed = 0;
        objectsRun = 0;
        setTimeMax(0);
        int configSlices = 100;
        if(configEntry != null)
            configSlices = mod.config.get(configEntry, configKeySlicesMax, configSlices).getInt();
        setSliceMax(configSlices);
        targetSliceMax = sliceMax;
    }

    @Override
    public String getName() { return name; }
    @Override
    public void loadConfig(boolean saveDefaults) {}
    @Override
    public void writeConfig(boolean saveFile) {}

    // Returns the number of tile entities in this group. Subclasses should override if relevant.
    public int getTileEntitiesCount() {
        if (tileEntityListManager != null) {
            return tileEntityListManager.size();
        }
        return 0;
    }

    public void startTimer() { startTime = System.nanoTime(); }
    public void startTimer(long startTime) { this.startTime = startTime; }
    public long endTimer() { long time = System.nanoTime() - startTime; timeUsed += time; return time; }

    @Override
    public void setTimeMax(long newTimeMax) {
        if(TickDynamicMod.debugTimer)
            System.out.println(name + ": setTimeMax: " + newTimeMax + " timeUsed: " + timeUsed);
        timeMax = newTimeMax;
    }
    @Override
    public long getTimeMax() { return timeMax; }
    /**
     * Set the target sliceMax. The actual sliceMax will ramp toward this value.
     */
    public void setSliceMax(int newSliceMax) {
        // On first assignment, initialize sliceMax immediately so groups don’t start at 0
        if (this.sliceMax == 0 && this.targetSliceMax == 0) {
            this.sliceMax = Math.max(0, newSliceMax);
        }
        this.targetSliceMax = Math.max(0, newSliceMax);
    }

    /**
     * Gradually ramp sliceMax toward targetSliceMax by at most rampRate per call.
     * Only allow unthrottling if parent TimeManager allows it.
     * Call this once per tick.
     */
    public void rampSliceMax() {
        boolean allowUnthrottle = true;
        try {
            if (mod != null && world != null) {
                com.wildex999.tickdynamic.timemanager.TimeManager tm = mod.getWorldTimeManager(world);
                if (tm != null) allowUnthrottle = tm.shouldAllowUnthrottle();
            }
        } catch(Throwable ignore) {}
        if (sliceMax < targetSliceMax && allowUnthrottle) {
            sliceMax = Math.min(sliceMax + rampRate, targetSliceMax);
        } else if (sliceMax > targetSliceMax) {
            sliceMax = Math.max(sliceMax - rampRate, targetSliceMax);
        }
    }

    public void setRampRate(int rate) {
        this.rampRate = rate;
    }

    public int getRampRate() {
        return rampRate;
    }
    @Override
    public int getSliceMax() { return sliceMax; }
    @Override
    public long getTimeUsed() { return timeUsed; }
    @Override
    public long getTimeUsedAverage() { return timeUsedAverage; }
    @Override
    public long getTimeUsedLast() { return prevTimeUsed; }
    @Override
    public long getReservedTime() { return 0; }
    public void setTimeUsed(long newTimeUsed) { timeUsed = newTimeUsed; }
    public int getObjectsRun() { return objectsRun; }
    public int getObjectsRunAverage() { return objectsRunAverage; }
    public int getObjectsRunLast() { return prevObjectsRun; }

    @Override
    public void newTick(boolean recursive) {
        rampSliceMax(); // Smoothly adjust sliceMax toward target each tick
        prevTimeUsed = timeUsed;
        prevObjectsRun = objectsRun;
        timeUsed = 0;
        objectsRun = 0;
        double smoothingFactor = 0.1;
        if(listTimeUsed.isEmpty()) {
            timeUsedAverage = prevTimeUsed;
            objectsRunAverage = prevObjectsRun;
        } else {
            timeUsedAverage = (long)(smoothingFactor * prevTimeUsed + (1.0 - smoothingFactor) * timeUsedAverage);
            objectsRunAverage = (int)(smoothingFactor * prevObjectsRun + (1.0 - smoothingFactor) * objectsRunAverage);
        }
        int maxHistorySize = Math.min(averageTicks, 100);
        if(listTimeUsed.size() >= maxHistorySize)
            listTimeUsed.removeFirst();
        listTimeUsed.add(prevTimeUsed);
        if(listObjectsRun.size() >= maxHistorySize)
            listObjectsRun.removeFirst();
        listObjectsRun.add(prevObjectsRun);
        if(timeUsedAverage < 10000) timeUsedAverage = 0;
        if(prevTimeUsed < 10000) prevTimeUsed = 0;
        if(timeUsedAverage < 0) timeUsedAverage = 0;
        if(objectsRunAverage < 0) objectsRunAverage = 0;
    }

    @Override
    public void endTick(boolean recursive) {}
    @Override
    public boolean isManager() { return false; }
}
