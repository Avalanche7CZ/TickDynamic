package com.wildex999.tickdynamic.util;

import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Method;

public class ModTileEntityUtils {
    private static volatile boolean lookedUp;
    private static Class<?> baseMetaTileEntityCls;
    private static Method getMetaTileEntityMethod;

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

    /**
     * Detect if a TileEntity is a GregTech multiblock controller.
     * Only controllers should be slowed down, not casings or passive parts.
     */
    public static boolean isGregTechMultiblockController(TileEntity te) {
        if (te == null) return false;
        ensureLookup();
        if (baseMetaTileEntityCls == null || getMetaTileEntityMethod == null) return false;
        try {
            if (!baseMetaTileEntityCls.isInstance(te)) return false;
            Object mte = getMetaTileEntityMethod.invoke(te);
            if (mte == null) return false;
            // Known GT5u class name in 1.7.10
            if (isClassOrSuperNamed(mte.getClass(), "gregtech.api.metatileentity.implementations.GT_MetaTileEntity_MultiblockBase")) return true;
            // Some forks use slightly different spellings/cases
            if (nameContains(mte.getClass(), "MultiblockBase") || nameContains(mte.getClass(), "MultiBlockBase")) return true;
            // Heuristic: has method isStructureFormed() or checkStructure(boolean)
            if (hasMethod(mte.getClass(), "isStructureFormed") || hasMethod(mte.getClass(), "checkStructure")) return true;
        } catch (Throwable ignored) {
            return false;
        }
        return false;
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
    private static boolean hasMethod(Class<?> c, String name) {
        try { c.getMethod(name); return true; } catch (Throwable ignored) { return false; }
    }
}
