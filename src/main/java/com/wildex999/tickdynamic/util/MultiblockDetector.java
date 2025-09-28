package com.wildex999.tickdynamic.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tileentity.TileEntity;

public class MultiblockDetector {
    private static final Map<String, MultiblockInfo> multiblockCache = new ConcurrentHashMap<String, MultiblockInfo>();
    private static final Map<Long, String> positionToMultiblock = new ConcurrentHashMap<Long, String>();
    private static long lastCleanup = 0;
    private static final long CLEANUP_INTERVAL = 30000;

    public static class MultiblockInfo {
        public final String type;
        public final String displayName;
        public final TileEntity controller;
        public final int dimId;
        private final Set<Long> positions;
        public boolean isActive;
        public long lastUpdate;

        public MultiblockInfo(String type, String displayName, TileEntity controller, int dimId) {
            this.type = type;
            this.displayName = displayName;
            this.controller = controller;
            this.dimId = dimId;
            this.positions = new HashSet<Long>();
            this.isActive = false;
            this.lastUpdate = System.currentTimeMillis();
        }

        public int getSize() { return positions.size(); }
        public void addPos(int dim, int x, int y, int z) { positions.add(packPosition(dim, x, y, z)); }
    }

    public static MultiblockInfo analyzeMultiblock(TileEntity te) {
        if (te == null || te.isInvalid()) return null;
        try {
            // First, try GregTech path (fast and precise when available)
            Object mte = getMetaTileEntity(te);
            if (mte != null) {
                boolean isCtrl = isGtMultiblockController(mte);
                TileEntity ctrl = isCtrl ? te : findGtController(te, mte);
                if (ctrl != null) {
                    String key = multiblockKey(ctrl);
                    MultiblockInfo info = multiblockCache.get(key);
                    if (info == null) {
                        String type = getGtMultiblockType(mte);
                        String disp = getGtMultiblockDisplayName(mte);
                        int dim = dimOf(ctrl);
                        info = new MultiblockInfo(type, disp, ctrl, dim);
                        multiblockCache.put(key, info);
                    }
                    info.lastUpdate = System.currentTimeMillis();
                    info.addPos(dimOf(te), te.xCoord, te.yCoord, te.zCoord);
                    info.isActive = detectGtActive(mte, info.isActive);
                    positionToMultiblock.put(packPosition(dimOf(te), te.xCoord, te.yCoord, te.zCoord), key);
                    return info;
                }
            }
            // Generic multiblock heuristics for non-GT controllers/parts
            TileEntity genCtrl = findGenericController(te);
            boolean isController = (genCtrl == te);
            TileEntity controller = isController ? te : genCtrl;
            if (controller == null) {
                // As a last resort, treat TEs with obvious controller naming as controllers
                if (looksLikeController(te)) controller = te; else return null;
            }
            String key = multiblockKey(controller);
            MultiblockInfo info = multiblockCache.get(key);
            if (info == null) {
                String type = getGenericType(controller);
                String disp = getGenericDisplayName(controller);
                info = new MultiblockInfo(type, disp, controller, dimOf(controller));
                multiblockCache.put(key, info);
            }
            info.lastUpdate = System.currentTimeMillis();
            info.addPos(dimOf(te), te.xCoord, te.yCoord, te.zCoord);
            boolean active = detectGenericActive(controller);
            info.isActive = info.isActive || active; // once active, keep until cleanup
            positionToMultiblock.put(packPosition(dimOf(te), te.xCoord, te.yCoord, te.zCoord), key);
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- GregTech helpers ----

    private static Object getMetaTileEntity(TileEntity te) {
        try {
            Class<?> teClass = te.getClass();
            try {
                Field f = teClass.getField("mMetaTileEntity");
                return f.get(te);
            } catch (NoSuchFieldException e) {
                try {
                    Method m = teClass.getMethod("getMetaTileEntity");
                    return m.invoke(te);
                } catch (NoSuchMethodException ex) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isGtMultiblockController(Object mte) {
        if (mte == null) return false;
        for(Class<?> c = mte.getClass(); c != null; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.contains("GT_MetaTileEntity_MultiblockBase") || n.contains("MultiblockBase") || n.contains("MultiBlockBase")) return true;
        }
        return false;
    }

    private static TileEntity findGtController(TileEntity te, Object mte) {
        try {
            Method m = mte.getClass().getMethod("getBaseMetaTileEntity");
            Object base = m.invoke(mte);
            if (base instanceof TileEntity) return (TileEntity) base;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getGtMultiblockType(Object mte) {
        try {
            Method m = mte.getClass().getMethod("getMetaName");
            Object r = m.invoke(mte);
            if (r != null) return String.valueOf(r);
        } catch (Throwable ignored) {}
        return mte.getClass().getSimpleName();
    }

    private static String getGtMultiblockDisplayName(Object mte) {
        try {
            Method m = mte.getClass().getMethod("getInventoryName");
            Object r = m.invoke(mte);
            if (r != null) return String.valueOf(r);
        } catch (Throwable ignored) {}
        return mte.getClass().getSimpleName();
    }

    private static boolean detectGtActive(Object mte, boolean prev) {
        try {
            try {
                Method m = mte.getClass().getMethod("isStructureFormed");
                Object r = m.invoke(mte);
                if (r instanceof Boolean) return ((Boolean) r).booleanValue();
            } catch (NoSuchMethodException ignore) {}
            try {
                Method m2 = mte.getClass().getMethod("checkStructure");
                Object r2 = m2.invoke(mte);
                if (r2 instanceof Boolean) return ((Boolean) r2).booleanValue();
            } catch (NoSuchMethodException ignore) {}
        } catch (Throwable ignored) {}
        return prev;
    }

    // ---- Generic heuristics ----

    private static TileEntity findGenericController(TileEntity te) {
        // Try common methods returning controller/master TE
        String[] meth = { "getController", "getMaster", "getControllerTE", "getMasterTile", "getOriginTE", "getCore" };
        for (String mname : meth) {
            try {
                Method m = te.getClass().getMethod(mname);
                Object r = m.invoke(te);
                if (r instanceof TileEntity) {
                    TileEntity c = (TileEntity) r;
                    if (c != null) return c;
                }
            } catch (Throwable ignored) {}
        }
        // Try fields commonly used
        String[] flds = { "controller", "master", "core" };
        for (String fname : flds) {
            try {
                Field f = te.getClass().getField(fname);
                Object r = f.get(te);
                if (r instanceof TileEntity) {
                    TileEntity c = (TileEntity) r;
                    if (c != null) return c;
                }
            } catch (Throwable ignored) {}
        }
        // No explicit controller: if it looks like a controller by name or flags, return itself
        if (looksLikeController(te)) return te;
        return null;
    }

    private static boolean looksLikeController(TileEntity te) {
        try {
            String n = te.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("controller")) return true;
            // “Master” flag often marks controller
            Boolean isMaster = tryBoolean(te, new String[]{ "isMaster", "getIsMaster" });
            if (isMaster != null && isMaster.booleanValue()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectGenericActive(TileEntity te) {
        // Prefer methods
        Boolean b = tryBoolean(te, new String[]{
                "isStructureFormed", "isFormed", "getIsFormed", "isAssembled", "getActive", "isActive", "isRunning"
        });
        if (b != null) return b.booleanValue();
        // Then fields
        Boolean f = tryBooleanField(te, new String[]{
                "formed", "structureFormed", "isFormed", "assembled", "active", "mActive"
        });
        return f != null ? f.booleanValue() : false;
    }

    private static Boolean tryBoolean(TileEntity te, String[] methods) {
        for (String mname : methods) {
            try {
                Method m = te.getClass().getMethod(mname);
                Object r = m.invoke(te);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Boolean tryBooleanField(TileEntity te, String[] fields) {
        for (String fname : fields) {
            try {
                Field f = te.getClass().getField(fname);
                Object r = f.get(te);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String getGenericType(TileEntity te) {
        try {
            net.minecraft.block.Block b = te.getWorldObj().getBlock(te.xCoord, te.yCoord, te.zCoord);
            String reg = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getNameForObject(b);
            if (reg != null) return reg.substring(reg.indexOf(':')+1);
        } catch (Throwable ignored) {}
        return te.getClass().getSimpleName();
    }

    private static String getGenericDisplayName(TileEntity te) {
        try {
            // Try inventory name methods
            String[] m = { "getInventoryName", "getLocalizedName", "getName" };
            for (String s : m) {
                try {
                    Method mm = te.getClass().getMethod(s);
                    Object r = mm.invoke(te);
                    if (r instanceof String) {
                        String str = (String) r;
                        if (str != null && !str.isEmpty()) return str;
                    }
                } catch (Throwable ignored) {}
            }
            // Fallback to Block ItemStack name
            net.minecraft.block.Block b = te.getWorldObj().getBlock(te.xCoord, te.yCoord, te.zCoord);
            int meta = te.getWorldObj().getBlockMetadata(te.xCoord, te.yCoord, te.zCoord);
            net.minecraft.item.Item it = net.minecraft.item.Item.getItemFromBlock(b);
            if (it != null) {
                net.minecraft.item.ItemStack st = new net.minecraft.item.ItemStack(it, 1, meta);
                String disp = st.getDisplayName();
                if (disp != null && !disp.isEmpty()) return disp;
            }
        } catch (Throwable ignored) {}
        return te.getClass().getSimpleName();
    }

    // ---- Keys / Utils ----

    private static int dimOf(TileEntity te) {
        try { return te.getWorldObj().provider.dimensionId; } catch (Throwable t) { return 0; }
    }

    private static String multiblockKey(TileEntity te) {
        int dim = dimOf(te);
        return te.getClass().getName() + ":" + dim + ":" + te.xCoord + ":" + te.yCoord + ":" + te.zCoord;
    }

    private static long packPosition(int dim, int x, int y, int z) {
        long h = (((long)x) * 73856093L) ^ (((long)y) * 19349663L) ^ (((long)z) * 83492791L);
        h ^= (((long)dim) * 2654435761L);
        return h;
    }

    public static void cleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL) return;
        lastCleanup = now;
        for (Iterator<Map.Entry<String, MultiblockInfo>> it = multiblockCache.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, MultiblockInfo> e = it.next();
            if (now - e.getValue().lastUpdate > CLEANUP_INTERVAL) it.remove();
        }
        for (Iterator<Map.Entry<Long, String>> it2 = positionToMultiblock.entrySet().iterator(); it2.hasNext();) {
            Map.Entry<Long, String> e = it2.next();
            if (!multiblockCache.containsKey(e.getValue())) it2.remove();
        }
    }
}
