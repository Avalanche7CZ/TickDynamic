package com.wildex999.tickdynamic.timemanager;

import java.util.ArrayList;
import java.util.Iterator;
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
        int allSlices = 0;
        int allSlicesPrev;
        List<ITimed> childrenLeft = new ArrayList<>(children);
        for(Iterator<ITimed> it = childrenLeft.iterator(); it.hasNext();) {
            ITimed timed = it.next();
            timed.setTimeMax(0);
            allSlices += timed.getSliceMax();
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
        allSlicesPrev = allSlices;
        boolean firstPass = true;
        while(leftover > 0 && childrenLeft.size() > 0) {
            long before = leftover;
            for(Iterator<ITimed> it = childrenLeft.iterator(); it.hasNext();) {
                ITimed child = it.next();
                long slice = 1 + (long)(before * ((double)child.getSliceMax() / (double)allSlices));
                if(firstPass) {
                    long reserved = child.getReservedTime();
                    slice -= reserved;
                    if(slice < 0) slice = 0;
                }
                long currentMax = child.getTimeMax() + slice;
                leftover -= slice;
                long left = currentMax - child.getTimeUsedAverage();
                if(left > (currentMax/100.0)) {
                    long giveBack = (long) (left-(currentMax/100.0));
                    leftover += giveBack;
                    currentMax -= giveBack;
                    it.remove();
                    allSlices -= child.getSliceMax();
                }
                child.setTimeMax(currentMax+1);
            }
            firstPass = false;
        }
        if(leftover > 0) {
            for(ITimed child : children ) {
                long slice = (long)(leftover * ((double)child.getSliceMax() / (double)allSlicesPrev));
                child.setTimeMax(child.getTimeMax() + slice);
            }
        }
        for(ITimed child : children) {
            if(child.isManager())
                ((TimeManager)child).balanceTime();
        }
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
        for(ITimed child : children) output += child.getTimeUsed();
        return output;
    }
    public long getTimeUsedAverage() {
        long output = 0;
        if(useCached) return cachedTimeUsedAverage;
        for(ITimed child : children) output += child.getTimeUsedAverage();
        return output;
    }
    public long getTimeUsedLast() {
        long output = 0;
        for(ITimed child : children) output += child.getTimeUsedLast();
        return output;
    }
    @Override
    public long getReservedTime() {
        long reservedTime = 0;
        if(useCached) return cachedTimeReserved;
        for(ITimed child : children) reservedTime += child.getReservedTime();
        return reservedTime;
    }
    @Override
    public void newTick(boolean recursive) {
        if(recursive) for(ITimed child : children) child.newTick(true);
        useCached = false;
    }
    public void endTick(boolean recursive) {
        if(recursive) for(ITimed child : children) child.endTick(recursive);
        cachedTimeUsedAverage = getTimeUsedAverage();
        cachedTimeReserved = getReservedTime();
    }
    @Override
    public boolean isManager() { return true; }
    public List<ITimed> getChildren() { return children; }
}
