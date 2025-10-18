package com.wildex999.tickdynamic.util;

import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModTileEntityUtils {
    private static volatile boolean lookedUp;
    private static Class<?> baseMetaTileEntityCls;
    private static Method getMetaTileEntityMethod;
    private static volatile String ctrlRegex = null;
    private static volatile java.util.regex.Pattern ctrlPattern = null;

    // Caches to avoid repeated reflection
    private static final Map<String, Boolean> methodCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> controllerCache = new ConcurrentHashMap<>();

    private static void ensureLookup() {
        if (lookedUp) return;
        synchronized (ModTileEntityUtils.class) {
            if (lookedUp) return;
            try {
                baseMetaTileEntityCls = Class.forName("gregtech.api.metatileentity.BaseMetaTileEntity");
                getMetaTileEntityMethod = baseMetaTileEntityCls.getMethod("getMetaTileEntity");
            } catch (Throwable t) {
                baseMetaTileEntityCls = null;
                getMetaTileEntityMethod = null;
            } finally {
                lookedUp = true;
            }
        }
    }

    private static void ensureRegex() {
        String prop = System.getProperty("tickdynamic.gt.controller.classRegex", "");
        if(prop != ctrlRegex) {
            ctrlRegex = prop;
            try { ctrlPattern = (prop!=null && !prop.trim().isEmpty()) ? java.util.regex.Pattern.compile(prop) : null; }
            catch(Throwable t){ ctrlPattern = null; }
        }
    }

    private static boolean hasMethodAnywhere(Class<?> c, String name) {
        if(c == null) return false;
        String key = c.getName() + "#" + name;
        Boolean cached = methodCache.get(key);
        if (cached != null) return cached;
        boolean found = false;
        for(Class<?> k=c; k!=null; k=k.getSuperclass()) {
            try { for(Method m : k.getDeclaredMethods()) { if(name.equals(m.getName())) { found = true; break; } } } catch(Throwable ignore) {}
            try { for(Method m : k.getMethods()) { if(name.equals(m.getName())) { found = true; break; } } } catch(Throwable ignore) {}
            if (found) break;
        }
        methodCache.put(key, found);
        return found;
    }

    private static boolean simpleNameLooksController(Class<?> c) {
        try {
            if(c == null) return false;
            String sn = c.getSimpleName(); if(sn == null) return false;
            String l = sn.toLowerCase(java.util.Locale.ROOT);
            return (l.contains("controller") || l.contains("multiblock") || l.contains("multi_block"));
        } catch(Throwable ignore) { return false; }
    }

    private static boolean packageLooksGtMulti(Class<?> c) {
        try {
            if(c == null) return false;
            String pn = c.getName(); if(pn == null) return false;
            String l = pn.toLowerCase(java.util.Locale.ROOT);
            // Common GTNH/GregTech package paths for multiblocks
            return (l.contains("gregtech") && (l.contains("multi") || l.contains("multiblock")));
        } catch(Throwable ignore) { return false; }
    }

    /**
     * Detect if a TileEntity is a GregTech multiblock controller.
     * Only controllers should be slowed down, not casings or passive parts.
     */
    public static boolean isGregTechMultiblockController(TileEntity te) {
        if (te == null) return false;
        ensureLookup();
        ensureRegex();
        Class<?> teClass = te.getClass();
        Boolean cached = controllerCache.get(teClass);
        if (cached != null) return cached;
        boolean result = false;
        // User-provided regex on TE itself takes priority if GT API unavailable
        if (baseMetaTileEntityCls == null || getMetaTileEntityMethod == null) {
            if(ctrlPattern != null) {
                try { result = ctrlPattern.matcher(teClass.getName()).find(); } catch(Throwable ignore) {}
            }
            controllerCache.put(teClass, result);
            return result;
        }
        try {
            if (!baseMetaTileEntityCls.isInstance(te)) {
                if(ctrlPattern != null) {
                    try { result = ctrlPattern.matcher(teClass.getName()).find(); } catch(Throwable ignore) {}
                }
                controllerCache.put(teClass, result);
                return result;
            }
            Object mte = getMetaTileEntityMethod.invoke(te);
            if (mte == null) {
                controllerCache.put(teClass, false);
                return false;
            }
            Class<?> mc = mte.getClass();
            if (isClassOrSuperNamed(mc, "gregtech.api.metatileentity.implementations.GT_MetaTileEntity_MultiblockBase")) result = true;
            else if (nameContains(mc, "MultiblockBase") || nameContains(mc, "MultiBlockBase") || nameContains(mc, "MultiBlockBase_EM")) result = true;
            else if (hasMethodAnywhere(mc, "isStructureFormed") || hasMethodAnywhere(mc, "checkStructure") || hasMethodAnywhere(mc, "checkMachine")) result = true;
            else if (simpleNameLooksController(mc) || packageLooksGtMulti(mc)) result = true;
            else if(ctrlPattern != null) {
                try { result = ctrlPattern.matcher(mc.getName()).find(); } catch(Throwable ignore) {}
            }
        } catch (Throwable ignored) {
            result = false;
        }
        controllerCache.put(teClass, result);
        return result;
    }

    private static boolean isClassOrSuperNamed(Class<?> c, String fqcn) {
        for(Class<?> k = c; k != null; k = k.getSuperclass()) {
            if(fqcn.equals(k.getName())) return true;
        }
        return false;
    }
    private static boolean nameContains(Class<?> c, String needle) {
        for(Class<?> k = c; k != null; k = k.getSuperclass()) {
            if(k.getName().contains(needle)) return true;
        }
        return false;
    }
}
