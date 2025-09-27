package com.wildex999.tickdynamic.timemanager;

public interface ITimed {
    long timeMilisecond = 1000000;
    long timeSecond = 1000000000;
    String configKeySlicesMax = "slicesMax";

    void init();
    void loadConfig(boolean saveDefaults);
    void writeConfig(boolean saveFile);
    void setTimeMax(long newTimeMax);
    long getTimeMax();
    void setSliceMax(int newSliceMax);
    int getSliceMax();
    long getTimeUsed();
    long getTimeUsedAverage();
    long getTimeUsedLast();
    long getReservedTime();
    void newTick(boolean recursive);
    void endTick(boolean recursive);
    boolean isManager();
    String getName();
}
