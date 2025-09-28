package com.wildex999.tickdynamic.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tileentity.TileEntity;

public class MultiblockDetector {
    private static final Map<String, MultiblockInfo> multiblockCache = new ConcurrentHashMap<String, MultiblockInfo>();
    private static final Map<Long, String> positionToMultiblock = new ConcurrentHashMap<Long, String>();
    private static final Map<Long, int[]> positionCoords = new ConcurrentHashMap<Long, int[]>();
    private static long lastCleanup = 0;
    private static final long CLEANUP_INTERVAL = 30000;
    private static final boolean DEBUG = Boolean.getBoolean("tickdynamic.multiblock.debug");

    private static final String[] EXCLUDE_TOKENS = new String[]{
        "hatch","pipe","cable","wire","transformer","bus","input","output","maintenance","muffler","receiver","emitter"
    };

    // Cache for TE class -> access to mMetaTileEntity/getMetaTileEntity
    private static final ConcurrentHashMap<Class<?>, TeAccess> TE_ACCESS = new ConcurrentHashMap<Class<?>, TeAccess>();
    private static final class TeAccess {
        Field metaField; Method metaGetter; boolean isGT;
    }
    // Cache for MTE class -> controller accessors (methods/fields)
    private static final ConcurrentHashMap<Class<?>, CtrlAccess> CTRL_ACCESS = new ConcurrentHashMap<Class<?>, CtrlAccess>();
    private static final class CtrlAccess {
        Method getController, getControllerMeta, getMaster, getMasterMeta;
        Field mController, controller, master;
    }
    // Per-position analysis cache with TTL in world ticks
    private static final long CACHE_TICKS = Long.getLong("tickdynamic.multiblock.cacheTicks", 40L);
    private static final ConcurrentHashMap<Long, CacheEntry> POS_CACHE = new ConcurrentHashMap<Long, CacheEntry>();
    private static final class CacheEntry { String key; long lastWorldTime; boolean active; int dim,x,y,z; }

    public static class MultiblockInfo {
        public final String type;
        public final String displayName;
        public final TileEntity controller;
        public final int dimId;
        private final Set<Pos> positions;
        public boolean isActive;
        public long lastUpdate;

        public MultiblockInfo(String type, String displayName, TileEntity controller, int dimId) {
            this.type = type;
            this.displayName = displayName;
            this.controller = controller;
            this.dimId = dimId;
            this.positions = new HashSet<Pos>();
            this.isActive = false;
            this.lastUpdate = System.currentTimeMillis();
        }

        public int getSize() { return positions.size(); }
        public void addPos(int dim, int x, int y, int z) { positions.add(new Pos(dim,x,y,z)); }
        public List<Pos> getPositionsSnapshot() { return new ArrayList<Pos>(positions); }
    }

    public static final class Pos {
        public final int dim;
        public final int x;
        public final int y;
        public final int z;
        public Pos(int dim, int x, int y, int z) { this.dim=dim; this.x=x; this.y=y; this.z=z; }
        @Override public int hashCode(){ int h=dim; h=31*h+x; h=31*h+y; h=31*h+z; return h; }
        @Override public boolean equals(Object o){ if(!(o instanceof Pos)) return false; Pos p=(Pos)o; return p.dim==dim && p.x==x && p.y==y && p.z==z; }
    }

    public static MultiblockInfo analyzeMultiblock(TileEntity te) {
        if (te == null || te.isInvalid()) return null;
        try {
            // Per-position cache with TTL to cut repeated reflection calls during scans
            long packedPos = packPosition(dimOf(te), te.xCoord, te.yCoord, te.zCoord);
            long wtime = 0L; try { wtime = te.getWorldObj().getTotalWorldTime(); } catch(Throwable ignore) {}
            CacheEntry ce = POS_CACHE.get(packedPos);
            if(ce != null && wtime > 0 && (wtime - ce.lastWorldTime) < CACHE_TICKS) {
                MultiblockInfo cachedInfo = ce.key != null ? multiblockCache.get(ce.key) : null;
                if(cachedInfo != null) {
                    cachedInfo.lastUpdate = System.currentTimeMillis();
                    cachedInfo.addPos(dimOf(te), te.xCoord, te.yCoord, te.zCoord);
                    positionToMultiblock.put(packedPos, ce.key);
                    positionCoords.put(packedPos, new int[]{dimOf(te), te.xCoord, te.yCoord, te.zCoord});
                    return cachedInfo;
                }
            }
            Object mte = getMetaTileEntity(te);
            if (mte != null) {
                if (isGtMultiblockController(mte)) {
                    MultiblockInfo out = record(te, mte, te);
                    CacheEntry ne = new CacheEntry(); ne.key = multiblockKey(out.controller); ne.lastWorldTime = wtime; ne.active = out.isActive; ne.dim=dimOf(te); ne.x=te.xCoord; ne.y=te.yCoord; ne.z=te.zCoord; POS_CACHE.put(packedPos, ne);
                    return out;
                } else {
                    TileEntity ctrl = resolveGtControllerFromMTE(mte);
                    if (ctrl != null) {
                        Object mteCtrl = getMetaFromTE(ctrl);
                        if (mteCtrl != null && isGtMultiblockController(mteCtrl)) {
                            MultiblockInfo out = record(te, mteCtrl, ctrl);
                            CacheEntry ne = new CacheEntry(); ne.key = multiblockKey(out.controller); ne.lastWorldTime = wtime; ne.active = out.isActive; ne.dim=dimOf(te); ne.x=te.xCoord; ne.y=te.yCoord; ne.z=te.zCoord; POS_CACHE.put(packedPos, ne);
                            return out;
                        } else {
                            CacheEntry ne = new CacheEntry(); ne.key = null; ne.lastWorldTime = wtime; ne.dim=dimOf(te); ne.x=te.xCoord; ne.y=te.yCoord; ne.z=te.zCoord; POS_CACHE.put(packedPos, ne);
                            return null;
                        }
                    } else {
                        CacheEntry ne = new CacheEntry(); ne.key = null; ne.lastWorldTime = wtime; ne.dim=dimOf(te); ne.x=te.xCoord; ne.y=te.yCoord; ne.z=te.zCoord; POS_CACHE.put(packedPos, ne);
                        return null;
                    }
                }
            }
            // Generic multiblock heuristics for non-GT
            TileEntity controller = findGenericController(te);
            if (controller == null) return null;
            if (looksLikePart(controller)) return null;
            String key = multiblockKey(controller);
            MultiblockInfo info = multiblockCache.get(key);
            if (info == null) {
                String type = getGenericType(controller);
                String disp = getGenericDisplayName(controller);
                info = new MultiblockInfo(type, disp, controller, dimOf(controller));
                multiblockCache.put(key, info);
                if (DEBUG) System.out.println("[TD][MB] Generic detect: type="+type+" name="+disp+" ctrl=("+controller.xCoord+","+controller.yCoord+","+controller.zCoord+") dim="+dimOf(controller));
            }
            info.lastUpdate = System.currentTimeMillis();
            info.addPos(dimOf(te), te.xCoord, te.yCoord, te.zCoord);
            boolean active = detectGenericActive(controller);
            info.isActive = info.isActive || active;
            long packed = packPosition(dimOf(te), te.xCoord, te.yCoord, te.zCoord);
            positionToMultiblock.put(packed, key);
            positionCoords.put(packed, new int[]{dimOf(te), te.xCoord, te.yCoord, te.zCoord});
            CacheEntry ne = new CacheEntry(); ne.key = key; ne.lastWorldTime = wtime; ne.active = info.isActive; ne.dim=dimOf(te); ne.x=te.xCoord; ne.y=te.yCoord; ne.z=te.zCoord; POS_CACHE.put(packed, ne);
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MultiblockInfo record(TileEntity source, Object mteController, TileEntity controllerTE) {
        String key = multiblockKey(controllerTE);
        MultiblockInfo info = multiblockCache.get(key);
        if (info == null) {
            String type = getGtMultiblockType(mteController);
            String disp = getGtMultiblockDisplayName(mteController);
            int dim = dimOf(controllerTE);
            info = new MultiblockInfo(type, disp, controllerTE, dim);
            multiblockCache.put(key, info);
            if (DEBUG) System.out.println("[TD][MB] GT detect: type="+type+" name="+disp+" ctrl=("+controllerTE.xCoord+","+controllerTE.yCoord+","+controllerTE.zCoord+") dim="+dim);
        }
        info.lastUpdate = System.currentTimeMillis();
        info.addPos(dimOf(source), source.xCoord, source.yCoord, source.zCoord);
        info.isActive = detectGtActive(mteController, info.isActive);
        long packed = packPosition(dimOf(source), source.xCoord, source.yCoord, source.zCoord);
        positionToMultiblock.put(packed, key);
        positionCoords.put(packed, new int[]{dimOf(source), source.xCoord, source.yCoord, source.zCoord});
        return info;
    }

    private static Object getMetaTileEntity(TileEntity te) { return getMetaFromTE(te); }

    private static Object getMetaFromTE(TileEntity te) {
        try {
            Class<?> teClass = te.getClass();
            String name = teClass.getName();
            if(name == null || !name.contains("gregtech")) return null; // fast bail on non-GT
            TeAccess acc = TE_ACCESS.get(teClass);
            if(acc == null) {
                acc = new TeAccess(); acc.isGT = true;
                try { acc.metaField = teClass.getField("mMetaTileEntity"); } catch (Throwable ignore) { acc.metaField = null; }
                if(acc.metaField == null) { try { acc.metaGetter = teClass.getMethod("getMetaTileEntity"); } catch(Throwable ignore) { acc.metaGetter = null; } }
                TE_ACCESS.put(teClass, acc);
            }
            if(acc.metaField != null) return acc.metaField.get(te);
            if(acc.metaGetter != null) return acc.metaGetter.invoke(te);
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TileEntity resolveGtControllerFromMTE(Object mte) {
        if(mte == null) return null;
        CtrlAccess ca = CTRL_ACCESS.get(mte.getClass());
        if(ca == null) {
            ca = new CtrlAccess();
            Class<?> c = mte.getClass();
            try { ca.getController = c.getMethod("getController"); } catch(Throwable ignore) {}
            try { ca.getControllerMeta = c.getMethod("getControllerMetaTileEntity"); } catch(Throwable ignore) {}
            try { ca.getMaster = c.getMethod("getMaster"); } catch(Throwable ignore) {}
            try { ca.getMasterMeta = c.getMethod("getMasterMetaTileEntity"); } catch(Throwable ignore) {}
            try { ca.mController = c.getField("mController"); } catch(Throwable ignore) {}
            try { ca.controller = c.getField("controller"); } catch(Throwable ignore) {}
            try { ca.master = c.getField("master"); } catch(Throwable ignore) {}
            CTRL_ACCESS.put(c, ca);
        }
        try {
            if(ca.getController != null) { Object cmte = ca.getController.invoke(mte); TileEntity te = toTE(cmte); if(te != null) return te; }
        } catch(Throwable ignore) {}
        try {
            if(ca.getControllerMeta != null) { Object cmte = ca.getControllerMeta.invoke(mte); TileEntity te = toTE(cmte); if(te != null) return te; }
        } catch(Throwable ignore) {}
        try {
            if(ca.getMaster != null) { Object cmte = ca.getMaster.invoke(mte); TileEntity te = toTE(cmte); if(te != null) return te; }
        } catch(Throwable ignore) {}
        try {
            if(ca.getMasterMeta != null) { Object cmte = ca.getMasterMeta.invoke(mte); TileEntity te = toTE(cmte); if(te != null) return te; }
        } catch(Throwable ignore) {}
        try {
            if(ca.mController != null) { Object cmte = ca.mController.get(mte); TileEntity te = toTE(cmte); if(te != null) return te; }
        } catch(Throwable ignore) {}
        try {
            if(ca.controller != null) { Object cmte = ca.controller.get(mte); TileEntity te = toTE(cmte); if(te != null) return te; }
        } catch(Throwable ignore) {}
        try {
            if(ca.master != null) { Object cmte = ca.master.get(mte); TileEntity te = toTE(cmte); if(te != null) return te; }
        } catch(Throwable ignore) {}
        return null;
    }

    private static TileEntity toTE(Object maybeMetaOrTE) {
        if (maybeMetaOrTE == null) return null;
        if (maybeMetaOrTE instanceof TileEntity) return (TileEntity) maybeMetaOrTE;
        try {
            Method mb = maybeMetaOrTE.getClass().getMethod("getBaseMetaTileEntity");
            Object b = mb.invoke(maybeMetaOrTE);
            if (b instanceof TileEntity) return (TileEntity) b;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isGtMultiblockController(Object mte) {
        if (mte == null) return false;
        try {
            Class<?> c = mte.getClass();
            for(; c != null; c = c.getSuperclass()) {
                String n = c.getName();
                if (n.contains("GT_MetaTileEntity_MultiblockBase") || n.contains("MultiblockBase") || n.contains("MultiBlockBase")) return true;
                if ((n.contains("gregtech") || n.contains("GT_MetaTileEntity")) && (n.contains("BlastFurnace") || n.contains("BrickedBlastFurnace") || n.contains("ElectricBlastFurnace") || n.contains("EBF") || n.contains("BBF"))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String getGtMultiblockType(Object mte) {
        try {
            Method m = mte.getClass().getMethod("getMetaName");
            Object r = m.invoke(mte);
            if (r != null) return String.valueOf(r);
        } catch (Throwable ignored) {}
        try {
            Field f = mte.getClass().getField("mName");
            Object r = f.get(mte);
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
        try {
            Method m2 = mte.getClass().getMethod("getLocalizedName");
            Object r2 = m2.invoke(mte);
            if (r2 != null) return String.valueOf(r2);
        } catch (Throwable ignored) {}
        return mte.getClass().getSimpleName();
    }

    private static boolean detectGtActive(Object mte, boolean prev) {
        boolean formed = false;
        boolean running = false;
        boolean progressing = false;
        try {
            // Structure formed check
            try {
                Method m = mte.getClass().getMethod("isStructureFormed");
                Object r = m.invoke(mte);
                if (r instanceof Boolean) formed = ((Boolean) r).booleanValue();
            } catch (NoSuchMethodException ignore) {}
            try {
                Method m2 = mte.getClass().getMethod("checkStructure");
                Object r2 = m2.invoke(mte);
                if (r2 instanceof Boolean) formed = formed || ((Boolean) r2).booleanValue();
            } catch (NoSuchMethodException ignore) {}
            // Running/active checks
            String[] methodNames = new String[]{ "getActive", "isActive", "isAllowedToWork", "isWorking", "isMachineActive", "isWorkingEnabled", "isProcessing", "readyToProcess", "hasWork" };
            for (String mn : methodNames) {
                try {
                    Method m = mte.getClass().getMethod(mn);
                    Object r = m.invoke(mte);
                    if (r instanceof Boolean && ((Boolean) r).booleanValue()) { running = true; break; }
                } catch (Throwable ignored) {}
            }
            String[] fieldNames = new String[]{ "mActive", "active", "isActive", "mMachine", "mRunning" };
            for (String fn : fieldNames) {
                try {
                    Field f = mte.getClass().getField(fn);
                    Object r = f.get(mte);
                    if (r instanceof Boolean && ((Boolean) r).booleanValue()) { running = true; break; }
                } catch (Throwable ignored) {}
            }
            String[] intMethodNames = new String[]{ "getEUt", "getMaxProgresstime", "getProgresstime", "getProgress", "getProgressTime", "getProgressMax" };
            for (String mn : intMethodNames) {
                Integer v = tryIntMethod(mte, mn);
                if (v != null && v.intValue() > 0) { progressing = true; break; }
            }
            String[] intFieldNames = new String[]{ "mEUt", "mMaxProgresstime", "mProgresstime", "mProgress", "mProgressTime" };
            for (String fn : intFieldNames) {
                Integer v = tryIntField(mte, fn);
                if (v != null && v.intValue() > 0) { progressing = true; break; }
            }
            try {
                Field logic = mte.getClass().getField("mProcessingLogic");
                Object pl = logic.get(mte);
                if (pl != null) {
                    Boolean b = tryBooleanObj(pl, new String[]{"isActive", "isWorking", "hasWork"});
                    if (b != null && b.booleanValue()) running = true;
                    Integer pi = tryIntMethod(pl, "getProgress");
                    if (pi != null && pi.intValue() > 0) progressing = true;
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return prev || formed || running || progressing;
    }

    private static Integer tryIntMethod(Object obj, String name) {
        try {
            Method m = obj.getClass().getMethod(name);
            Object r = m.invoke(obj);
            if (r instanceof Number) return Integer.valueOf(((Number) r).intValue());
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer tryIntField(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            Object r = f.get(obj);
            if (r instanceof Number) return Integer.valueOf(((Number) r).intValue());
        } catch (Throwable ignored) {}
        return null;
    }

    private static Boolean tryBooleanObj(Object obj, String[] names) {
        for (String n : names) {
            try {
                Method m = obj.getClass().getMethod(n);
                Object r = m.invoke(obj);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static TileEntity findGenericController(TileEntity te) {
        String[] meth = { "getController", "getMaster", "getControllerTE", "getMasterTile", "getOriginTE", "getCore" };
        for (String mname : meth) {
            try {
                Method m = te.getClass().getMethod(mname);
                Object r = m.invoke(te);
                TileEntity c = (r instanceof TileEntity) ? (TileEntity) r : toTE(r);
                if (c != null) return c;
            } catch (Throwable ignored) {}
        }
        String[] flds = { "controller", "master", "core" };
        for (String fname : flds) {
            try {
                Field f = te.getClass().getField(fname);
                Object r = f.get(te);
                TileEntity c = (r instanceof TileEntity) ? (TileEntity) r : toTE(r);
                if (c != null) return c;
            } catch (Throwable ignored) {}
        }
        if (looksLikeController(te) && !looksLikePart(te)) return te;
        return null;
    }

    private static boolean looksLikeController(TileEntity te) {
        try {
            String n = te.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            return n.contains("controller");
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean looksLikePart(TileEntity te) {
        try {
            String n = te.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            for (String t : EXCLUDE_TOKENS) if (n.contains(t)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectGenericActive(TileEntity te) {
        Boolean b = tryBoolean(te, new String[]{
                "isStructureFormed", "isFormed", "getIsFormed", "isAssembled", "getActive", "isActive", "isRunning"
        });
        if (b != null) return b.booleanValue();
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

    private static int[] unpackPosition(long packed) {
        int[] coords = positionCoords.get(packed);
        if (coords != null && coords.length == 4) return new int[]{coords[0], coords[1], coords[2], coords[3]};
        return new int[]{0,0,0,0};
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
            if (!multiblockCache.containsKey(e.getValue())) {
                it2.remove();
                positionCoords.remove(e.getKey());
                POS_CACHE.remove(e.getKey());
            }
        }
        for (Iterator<Map.Entry<Long, int[]>> it3 = positionCoords.entrySet().iterator(); it3.hasNext();) {
            Map.Entry<Long, int[]> e = it3.next();
            if (!positionToMultiblock.containsKey(e.getKey())) { it3.remove(); POS_CACHE.remove(e.getKey()); }
        }
    }
}
