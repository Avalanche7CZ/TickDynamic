package com.wildex999.tickdynamic.util;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class MultiblockDetector {
    private static final Map<String, MultiblockInfo> multiblockCache = new ConcurrentHashMap<>();
    private static final Map<Long, String> positionToMultiblock = new ConcurrentHashMap<>();
    private static long lastCleanup = 0;
    private static final long CLEANUP_INTERVAL = 30000;

    public static class MultiblockInfo {
        public final String type;
        public final String displayName;
        public final Set<Long> positions;
        public final TileEntity controller;
        public final int dimId;
        public boolean isActive;
        public long lastUpdate;

        public MultiblockInfo(String type, String displayName, TileEntity controller, int dimId) {
            this.type = type;
            this.displayName = displayName;
            this.controller = controller;
            this.dimId = dimId;
            this.positions = new HashSet<>();
            this.isActive = false;
            this.lastUpdate = System.currentTimeMillis();
        }

        public int getSize() { return positions.size(); }
    }

    public static MultiblockInfo analyzeMultiblock(TileEntity te) {
        if (te == null || te.isInvalid()) return null;
        try {
            Class<?> teClass = te.getClass();
            String className = teClass.getName();
            if (!className.contains("gregtech") && !className.contains("BaseMetaTileEntity")) return null;
            Object metaTileEntity = getMetaTileEntity(te);
            if (metaTileEntity == null) return null;
            if (isMultiblockController(metaTileEntity)) return analyzeMultiblockController(te, metaTileEntity);
            TileEntity controller = findMultiblockController(te, metaTileEntity);
            if (controller != null) {
                String key = getMultiblockKey(controller);
                MultiblockInfo existing = multiblockCache.get(key);
                if (existing != null) {
                    long pos = packPosition(te.xCoord, te.yCoord, te.zCoord);
                    existing.positions.add(pos);
                    positionToMultiblock.put(pos, key);
                    existing.lastUpdate = System.currentTimeMillis();
                    return existing;
                }
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private static MultiblockInfo analyzeMultiblockController(TileEntity te, Object metaTileEntity) {
        try {
            String multiblockType = getMultiblockType(metaTileEntity);
            String displayName = getMultiblockDisplayName(metaTileEntity);
            int dimId = te.getWorldObj().provider.dimensionId;
            String key = getMultiblockKey(te);
            MultiblockInfo info = multiblockCache.get(key);
            if (info == null) {
                info = new MultiblockInfo(multiblockType, displayName, te, dimId);
                multiblockCache.put(key, info);
            }
            info.lastUpdate = System.currentTimeMillis();
            return info;
        } catch (Exception e) { return null; }
    }

    private static Object getMetaTileEntity(TileEntity te) { return null; }
    private static boolean isMultiblockController(Object metaTileEntity) { return false; }
    private static TileEntity findMultiblockController(TileEntity te, Object metaTileEntity) { return null; }
    private static String getMultiblockKey(TileEntity te) { return te.getClass().getName() + ":" + te.xCoord + ":" + te.yCoord + ":" + te.zCoord; }
    private static String getMultiblockType(Object metaTileEntity) { return ""; }
    private static String getMultiblockDisplayName(Object metaTileEntity) { return ""; }
    private static long packPosition(int x, int y, int z) { return (((long)x) << 40) | (((long)y) << 20) | ((long)z); }

    public static void cleanup() {
        long now = System.currentTimeMillis();
        if(now - lastCleanup < CLEANUP_INTERVAL) return;
        lastCleanup = now;
        Iterator<Map.Entry<String, MultiblockInfo>> it = multiblockCache.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String, MultiblockInfo> entry = it.next();
            if(now - entry.getValue().lastUpdate > CLEANUP_INTERVAL) it.remove();
        }
        Iterator<Map.Entry<Long, String>> pit = positionToMultiblock.entrySet().iterator();
        while(pit.hasNext()) {
            Map.Entry<Long, String> entry = pit.next();
            if(!multiblockCache.containsKey(entry.getValue())) pit.remove();
        }
    }
}
