package com.wildex999.tickdynamic.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

public final class ModResolver {
    private static final Map<String,String> sourceNameToModId = new HashMap<>();
    private static final Map<String,String> sourceNameToModName = new HashMap<>();
    private static volatile boolean initialized = false;

    private ModResolver() {}

    private static void ensureInit() {
        if(initialized) return;
        synchronized (ModResolver.class) {
            if(initialized) return;
            for(ModContainer mod : Loader.instance().getModList()) {
                try {
                    String src = mod.getSource() == null ? null : mod.getSource().getName();
                    if(src == null) continue;
                    sourceNameToModId.put(src, mod.getModId());
                    sourceNameToModName.put(src, mod.getName());
                } catch(Throwable ignored) {}
            }
            sourceNameToModName.put("Forge", "Minecraft");
            sourceNameToModId.put("Forge", "Minecraft");
            initialized = true;
        }
    }

    public static String modIdFromClass(Class<?> cls) {
        if(cls == null) return "";
        ensureInit();
        try {
            String loc = cls.getProtectionDomain().getCodeSource().getLocation().toString();
            String dec;
            try { dec = URLDecoder.decode(loc, "UTF-8"); } catch(UnsupportedEncodingException e) { dec = loc; }
            for(Map.Entry<String,String> e : sourceNameToModId.entrySet()) {
                if(dec.contains(e.getKey())) return e.getValue();
            }
        } catch(Throwable ignored) {}
        return "";
    }

    public static String modNameFromClass(Class<?> cls) {
        if(cls == null) return "";
        ensureInit();
        try {
            String loc = cls.getProtectionDomain().getCodeSource().getLocation().toString();
            String dec;
            try { dec = URLDecoder.decode(loc, "UTF-8"); } catch(UnsupportedEncodingException e) { dec = loc; }
            for(Map.Entry<String,String> e : sourceNameToModName.entrySet()) {
                if(dec.contains(e.getKey())) return e.getValue();
            }
        } catch(Throwable ignored) {}
        return "";
    }
}
