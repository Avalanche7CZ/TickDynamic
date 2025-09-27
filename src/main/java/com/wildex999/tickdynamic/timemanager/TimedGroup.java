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

    public TimedGroup(TickDynamicMod mod, World world, String name, String configEntry) {
        if(configEntry != null)
            mod.timedObjects.put(configEntry, this);
        else
            mod.timedObjects.put(name, this);
        this.name = name;
        this.mod = mod;
        this.world = world;
        this.configEntry = configEntry;
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
    }

    @Override
    public String getName() { return name; }
    @Override
    public void loadConfig(boolean saveDefaults) {}
    @Override
    public void writeConfig(boolean saveFile) {}

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
    @Override
    public void setSliceMax(int newSliceMax) { sliceMax = newSliceMax; }
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
