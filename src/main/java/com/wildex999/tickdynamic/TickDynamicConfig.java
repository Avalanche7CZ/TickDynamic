package com.wildex999.tickdynamic;

import java.util.ArrayList;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;

import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.listinject.EntityType;
import com.wildex999.tickdynamic.listinject.ListManager;
import com.wildex999.tickdynamic.timemanager.ITimed;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedEntities;

public class TickDynamicConfig {
    public static void loadConfig(TickDynamicMod mod, boolean groups) {
        // Preserve existing Configuration instance to avoid discarding unsaved queued changes.
        if(mod.config != null) {
            try { mod.config.load(); } catch(Exception ignored) {}
        }
        mod.config.getCategory("general");
        mod.config.setCategoryComment("general", "TickDynamic core settings");
        mod.enabled = mod.config.get("general", "enabled", true, "Master enable/disable").getBoolean();
        mod.debug = mod.config.get("general", "debug", mod.debug, "General debug output").getBoolean();
        mod.debugGroups = mod.config.get("general", "debugGroups", mod.debugGroups, "Debug group mapping").getBoolean();
        mod.debugTimer = mod.config.get("general", "debugTimer", mod.debugTimer, "Debug timer output").getBoolean();
        // New: allow disabling remote version checker
        mod.versionCheckEnabled = mod.config.get("general", "versionCheckEnabled", mod.versionCheckEnabled, "Enable remote version check on server start (can also be set with -Dtickdynamic.versionCheck.enabled=false)").getBoolean(mod.versionCheckEnabled);
        mod.defaultWorldSlicesMax = mod.config.get("general", "defaultWorldSlicesMax", mod.defaultWorldSlicesMax, "Default world sliceMax").getInt();
        mod.defaultAverageTicks = mod.config.get("general", "averageTicks", mod.defaultAverageTicks, "Ticks used when averaging time").getInt();
        mod.activationTpsThreshold = mod.config.get("general", "activationTpsThreshold", mod.activationTpsThreshold, "If >0 dynamic limiting only active below this TPS").getDouble(mod.activationTpsThreshold);
        mod.activationTpsActivateBelow = mod.config.get("general", "dynamicHysteresisActivateBelow", mod.activationTpsActivateBelow, "If >0 with dynamicHysteresisDeactivateAbove set higher, dynamic balancing activates when TPS falls below this (hysteresis mode).\nSet both to 0 to disable hysteresis.").getDouble(mod.activationTpsActivateBelow);
        mod.activationTpsDeactivateAbove = mod.config.get("general", "dynamicHysteresisDeactivateAbove", mod.activationTpsDeactivateAbove, "Upper hysteresis bound: dynamic balancing deactivates when TPS rises above this. Must be higher than dynamicHysteresisActivateBelow.").getDouble(mod.activationTpsDeactivateAbove);
        mod.colorHysteresisMarginPercent = mod.config.get("general", "colorHysteresisMarginPercent", mod.colorHysteresisMarginPercent, "Margin applied to percent color bands to reduce flicker (e.g. 2.0 = 2%). Set 0 to disable. Recommended 0-3.").getDouble(mod.colorHysteresisMarginPercent);
        // Clamp values to sane ranges
        if(mod.activationTpsActivateBelow < 0) mod.activationTpsActivateBelow = 0;
        if(mod.activationTpsDeactivateAbove < 0) mod.activationTpsDeactivateAbove = 0;
        if(mod.colorHysteresisMarginPercent < 0) mod.colorHysteresisMarginPercent = 0;
        if(mod.colorHysteresisMarginPercent > 20) mod.colorHysteresisMarginPercent = 20; // hard cap
        // If ordering invalid, disable hysteresis gracefully
        if(!(mod.activationTpsActivateBelow > 0 && mod.activationTpsDeactivateAbove > mod.activationTpsActivateBelow)) {
            mod.activationTpsActivateBelow = 0;
            mod.activationTpsDeactivateAbove = 0;
        }
        // Initialize dynamicActive for hysteresis: start inactive if hysteresis configured
        if(mod.activationTpsActivateBelow > 0 && mod.activationTpsDeactivateAbove > mod.activationTpsActivateBelow) mod.dynamicActive = false; else if(mod.activationTpsThreshold <= 0) mod.dynamicActive = true;

        mod.defaultTickTime = mod.config.get("worlds", "tickTime", mod.defaultTickTime, "Target tick time ms").getInt();

        // Web dashboard configuration
        mod.config.getCategory("web");
        mod.config.setCategoryComment("web", "Web dashboard (experimental).\nSet bind to 127.0.0.1 to allow local-only access or 0.0.0.0 to listen on all interfaces.\nIf token is non-empty, clients must provide ?token=VALUE or X-Auth-Token header.");
        boolean prevWebEnabled = mod.webEnabled;
        String prevBind = mod.webBind;
        int prevPort = mod.webPort;
        String prevToken = mod.webToken;
        mod.webEnabled = mod.config.get("web", "enabled", mod.webEnabled, "Enable web dashboard HTTP server").getBoolean(mod.webEnabled);
        mod.webBind = mod.config.get("web", "bind", mod.webBind, "Bind address (e.g. 127.0.0.1 for local-only, 0.0.0.0 for all interfaces)").getString();
        mod.webPort = mod.config.get("web", "port", mod.webPort > 0 ? mod.webPort : 9777, "TCP port for dashboard").getInt();
        mod.webToken = mod.config.get("web", "token", mod.webToken, "Optional access token; leave blank to disable auth").getString();
        if(mod.webPort < 1 || mod.webPort > 65535) mod.webPort = 9777;
        // Hot-apply web changes if server is running
        if(mod.server != null) {
            boolean changed = (prevWebEnabled != mod.webEnabled) ||
                              (mod.webEnabled && (!safeEq(prevBind, mod.webBind) || prevPort != mod.webPort || !safeEq(prevToken, mod.webToken)));
            if(changed) {
                if(mod.webServer != null) { try { mod.webServer.stop(); } catch(Exception ignore) {} mod.webServer = null; }
                if(mod.webEnabled) {
                    try {
                        mod.webServer = new com.wildex999.tickdynamic.web.WebServer(mod, mod.webBind, mod.webPort, mod.webToken);
                        mod.webServer.start();
                        System.out.println("[TickDynamic][Web] Restarted on http://"+mod.webBind+":"+mod.webPort+" (token "+(mod.webToken.isEmpty()?"disabled":"enabled")+")");
                    } catch(Exception ex) {
                        System.err.println("[TickDynamic][Web] Failed to start web dashboard: " + ex.getMessage());
                        ex.printStackTrace();
                        mod.webServer = null;
                        mod.webEnabled = false;
                    }
                } else {
                    System.out.println("[TickDynamic][Web] Disabled via config.");
                }
            }
        }

        mod.config.getCategory("c2me");
        mod.config.setCategoryComment("c2me", "Lightweight chunk prefetch (NOT full async) – spreads generation cost earlier while TPS is healthy.");
        boolean prevEnabled = mod.c2meEnabled;
        mod.c2meEnabled = mod.config.get("c2me", "enabled", mod.c2meEnabled, "Enable chunk prefetch system").getBoolean();
        mod.c2mePrefetchRadius = mod.config.get("c2me", "prefetchRadius", mod.c2mePrefetchRadius, "Chunks beyond view distance to prefetch (0 disables)").getInt();
        mod.c2mePrefetchPerTick = mod.config.get("c2me", "prefetchPerTick", mod.c2mePrefetchPerTick, "Max chunks to prefetch per tick").getInt();
        mod.c2mePrefetchMinTps = mod.config.get("c2me", "prefetchMinTps", mod.c2mePrefetchMinTps, "Only prefetch while TPS >= this").getDouble(mod.c2mePrefetchMinTps);
        mod.c2meDebug = mod.config.get("c2me", "debug", mod.c2meDebug, "Extra logging for prefetch").getBoolean();
        String[] dimWL = mod.config.get("c2me", "dimensionWhitelist", new String[0], "Optional dimension id whitelist; empty = all").getStringList();
        mod.c2meDimensionWhitelist = dimWL;
        // Clamp C2ME values
        if(mod.c2mePrefetchRadius < 0) mod.c2mePrefetchRadius = 0; else if(mod.c2mePrefetchRadius > 64) mod.c2mePrefetchRadius = 64;
        if(mod.c2mePrefetchPerTick < 0) mod.c2mePrefetchPerTick = 0; else if(mod.c2mePrefetchPerTick > 500) mod.c2mePrefetchPerTick = 500;
        if(mod.c2mePrefetchMinTps < 0) mod.c2mePrefetchMinTps = 0; else if(mod.c2mePrefetchMinTps > 20) mod.c2mePrefetchMinTps = 20;
        if(prevEnabled != mod.c2meEnabled) {
            if(mod.c2meEnabled && mod.c2meManager == null) mod.c2meManager = new com.wildex999.tickdynamic.c2me.C2MEManager(mod);
            else if(!mod.c2meEnabled && mod.c2meManager != null) { mod.c2meManager.shutdown(); mod.c2meManager = null; }
        }

        // Isolated balancing configuration (player-weight fairness and extras)
        mod.config.getCategory("isolated");
        mod.config.setCategoryComment("isolated", "Per-dimension balancing with player weighting and protections.");
        mod.isolatedMode = mod.config.get("isolated", "enabled", mod.isolatedMode, "Enable isolated per-dimension balancing").getBoolean(mod.isolatedMode);
        mod.isolatedSkipNoPlayers = mod.config.get("isolated", "skipNoPlayers", mod.isolatedSkipNoPlayers, "Skip worlds with no players (weight=0)").getBoolean(mod.isolatedSkipNoPlayers);
        mod.isolatedPlayerWeight = mod.config.get("isolated", "playerWeight", mod.isolatedPlayerWeight, "Weight budgets by player count").getBoolean(mod.isolatedPlayerWeight);
        mod.isolatedPlayerWeightScale = mod.config.get("isolated", "playerWeightScale", mod.isolatedPlayerWeightScale, "Weight scale per player (weight = 1 + players*scale)").getDouble(mod.isolatedPlayerWeightScale);
        mod.isolatedPlayerWeightMax = mod.config.get("isolated", "playerWeightMax", mod.isolatedPlayerWeightMax, "Clamp for player-weight multiplier").getDouble(mod.isolatedPlayerWeightMax);
        mod.isolatedProtectSpecial = mod.config.get("isolated", "protectSpecial", mod.isolatedProtectSpecial, "Treat special worlds as protected").getBoolean(mod.isolatedProtectSpecial);
        // Extra: reduce weight for worlds with zero TileEntities even if players are present
        mod.isolatedLessIfNoTiles = mod.config.get("isolated", "lessIfNoTiles", mod.isolatedLessIfNoTiles, "Reduce world weight if it has zero TileEntities loaded").getBoolean(mod.isolatedLessIfNoTiles);
        mod.isolatedNoTilesFactor = mod.config.get("isolated", "noTilesFactor", mod.isolatedNoTilesFactor, "Factor to apply to world weight when it has zero TEs (0.0-1.0)").getDouble(mod.isolatedNoTilesFactor);

        // Offender-first deprioritization config
        mod.config.getCategory("offender");
        mod.config.setCategoryComment("offender", "TileEntity offender deprioritization (graceful, intensity-aware)");
        mod.tileOffenderDeprioritize = mod.config.get("offender", "enabled", mod.tileOffenderDeprioritize, "Enable offender-first TE deprioritization").getBoolean(mod.tileOffenderDeprioritize);
        mod.tileOffenderTop = mod.config.get("offender", "top", mod.tileOffenderTop, "Max offenders to consider per dimension").getInt(mod.tileOffenderTop);
        mod.tileOffenderMinMs = mod.config.get("offender", "minMs", mod.tileOffenderMinMs, "Minimum total time (ms) to consider as offender").getDouble(mod.tileOffenderMinMs);
        mod.tileOffenderMaxPerChunk = mod.config.get("offender", "maxPerChunk", mod.tileOffenderMaxPerChunk, "Max penalized offenders per chunk").getInt(mod.tileOffenderMaxPerChunk);
        mod.tileOffenderSkipEvery = mod.config.get("offender", "skipEvery", mod.tileOffenderSkipEvery, "Every Nth tick do not skip (cadence)").getInt(mod.tileOffenderSkipEvery);
        mod.tileOffenderPenaltyUp = mod.config.get("offender", "penaltyUp", mod.tileOffenderPenaltyUp, "Penalty ramp-up per tick (severity-scaled)").getDouble(mod.tileOffenderPenaltyUp);
        mod.tileOffenderPenaltyDown = mod.config.get("offender", "penaltyDown", mod.tileOffenderPenaltyDown, "Penalty decay per tick").getDouble(mod.tileOffenderPenaltyDown);
        mod.tileOffenderSkipMinPenalty = mod.config.get("offender", "skipMinPenalty", mod.tileOffenderSkipMinPenalty, "Minimum penalty before skipping applies").getDouble(mod.tileOffenderSkipMinPenalty);
        mod.tileOffenderControllerGain = mod.config.get("offender", "controllerGain", mod.tileOffenderControllerGain, "Gain scaling with tick error (ms)").getDouble(mod.tileOffenderControllerGain);
        mod.tileOffenderControllerMin = mod.config.get("offender", "controllerMin", mod.tileOffenderControllerMin, "Minimum gain when active").getDouble(mod.tileOffenderControllerMin);
        mod.tileOffenderControllerMax = mod.config.get("offender", "controllerMax", mod.tileOffenderControllerMax, "Maximum gain when active").getDouble(mod.tileOffenderControllerMax);
        mod.tileOffenderGlobalCapPercent = mod.config.get("offender", "globalCapPercent", mod.tileOffenderGlobalCapPercent, "Max fraction of TEs penalized (0.0-1.0)").getDouble(mod.tileOffenderGlobalCapPercent);
        mod.tileOffenderNearPlayerRadius = mod.config.get("offender", "nearPlayerRadius", mod.tileOffenderNearPlayerRadius, "Radius for near-player bias").getInt(mod.tileOffenderNearPlayerRadius);
        mod.tileOffenderNearPlayerBias = mod.config.get("offender", "nearPlayerBias", mod.tileOffenderNearPlayerBias, "Multiplier for skip probability near players (0.0-1.0)").getDouble(mod.tileOffenderNearPlayerBias);
        mod.tileOffenderExcludeClassRegex = mod.config.get("offender", "excludeClassRegex", mod.tileOffenderExcludeClassRegex, "Regex for TE classes to exclude from penalization").getString();
        try { mod.tileOffenderExcludeClassPattern = (mod.tileOffenderExcludeClassRegex==null||mod.tileOffenderExcludeClassRegex.trim().isEmpty())?null:java.util.regex.Pattern.compile(mod.tileOffenderExcludeClassRegex); } catch(Throwable t){ mod.tileOffenderExcludeClassPattern=null; }

        if(groups) {
            loadGlobalGroups(mod);
            if(!mod.config.hasCategory("worlds.dim0.entity")) {
                mod.config.get("worlds.dim0.entity", ITimed.configKeySlicesMax, mod.defaultEntitySlicesMax);
                mod.config.get("worlds.dim0.entity", EntityGroup.config_groupType, EntityType.Entity.toString());
            }
            if(!mod.config.hasCategory("worlds.dim0.tileentity")) {
                mod.config.get("worlds.dim0.tileentity", ITimed.configKeySlicesMax, mod.defaultEntitySlicesMax);
                mod.config.get("worlds.dim0.tileentity", EntityGroup.config_groupType, EntityType.TileEntity.toString());
            }
            WorldServer[] worlds = DimensionManager.getWorlds();
            for(WorldServer w : worlds) {
                if(mod.debug) System.out.println("Reloading "+w.provider.getDimensionName());
                if(w.loadedEntityList instanceof ListManager) ((ListManager)w.loadedEntityList).reloadGroups();
                if(w.loadedTileEntityList instanceof ListManager) ((ListManager)w.loadedTileEntityList).reloadGroups();
            }
            if(mod.debug) System.out.println("Done reloading worlds");
            // Iterate over a snapshot to avoid ConcurrentModificationException when removing invalid timed objects
            for(ITimed t : new ArrayList<ITimed>(mod.timedObjects.values())) {
                if(t instanceof TimedEntities) {
                    TimedEntities tg = (TimedEntities)t;
                    if(!tg.getEntityGroup().valid) { mod.timedObjects.remove(tg); continue; }
                }
                t.loadConfig(false);
            }
            if(mod.root != null) mod.root.setTimeMax(mod.defaultTickTime * TimeManager.timeMilisecond);
        } else {
            // Non-group reload: still apply updated sliceMax & other per-timed settings
            for(ITimed t : new ArrayList<ITimed>(mod.timedObjects.values())) {
                t.loadConfig(false);
            }
            if(mod.root != null) mod.root.setTimeMax(mod.defaultTickTime * TimeManager.timeMilisecond);
        }
        mod.config.save();
    }

    public static void loadGlobalGroups(TickDynamicMod mod) {
        mod.config.setCategoryComment("groups", "Global entity/tile groups");
        loadDefaultGlobalGroups(mod); loadGroups(mod,"groups");
    }

    public static void loadGroups(TickDynamicMod mod, String category) {
        ConfigCategory cat = mod.config.getCategory(category);
        Set<ConfigCategory> groups = cat.getChildren();
        ArrayList<String> remove = new ArrayList<String>();
        for(String gp : mod.entityGroups.keySet()) {
            if(!gp.startsWith(category)) continue;
            int idx = gp.lastIndexOf('.');
            String gName = idx==-1?gp:gp.substring(idx+1);
            boolean rem = !mod.config.hasCategory(gp);
            if(rem) {
                EntityGroup eg = mod.entityGroups.get(gp);
                if(eg!=null && eg.base!=null && mod.config.hasCategory("groups."+gName)) rem=false;
            }
            if(rem) { if(mod.debug) System.out.println("Remove Group: "+gp); remove.add(gp);} }
        for(String r: remove) { EntityGroup g = mod.entityGroups.remove(r); if(g!=null) g.valid=false; }
        for(ConfigCategory gCat : groups) {
            String path = category+"."+gCat.getName();
            EntityGroup eg = mod.getEntityGroup(path);
            if(eg==null) {
                if(mod.debug) System.out.println("Loading group: "+path);
                TimedEntities te = (TimedEntities)mod.getTimedGroup(path);
                if(te==null) { te = new TimedEntities(mod,null,gCat.getName(),path,null); te.init(); }
                EntityGroup newG = new EntityGroup(mod,null,te,gCat.getName(),path,EntityType.Entity,null);
                mod.entityGroups.put(path,newG);
                if(mod.debug) System.out.println("New Group: "+path);
            }else if(mod.debug) System.out.println("Update Group: "+path);
        }
        for(EntityGroup eg : new ArrayList<EntityGroup>(mod.entityGroups.values())) {
            if(eg.getName() == null) continue;
            if(!eg.valid) continue;
            if(!eg.getName().equals("entity") && !eg.getName().equals("players") && !eg.getName().equals("tileentity")) eg.readConfig(false);
        }
    }

    public static void loadDefaultGlobalGroups(TickDynamicMod mod) {
        EntityGroup g; TimedEntities tg; String path;
        path = "groups.entity"; g = mod.getEntityGroup(path); if(g==null){ tg=new TimedEntities(mod,null,"entity",path,null); tg.init(); g=new EntityGroup(mod,null,tg,"entity",path,EntityType.Entity,null); mod.entityGroups.put(path,g);}
        path = "groups.players"; g=mod.getEntityGroup(path); if(g==null){ mod.config.get(path,TimedEntities.configKeySlicesMax,0); String[] cls={EntityPlayer.class.getName(),EntityPlayerMP.class.getName()}; mod.config.get(path,EntityGroup.config_classNames,cls); tg=new TimedEntities(mod,null,"players",path,null); tg.init(); g=new EntityGroup(mod,null,tg,"players",path,EntityType.Entity,null); mod.entityGroups.put(path,g);}
        path = "groups.tileentity"; g=mod.getEntityGroup(path); if(g==null){ tg=new TimedEntities(mod,null,"tileentity",path,null); tg.init(); g=new EntityGroup(mod,null,tg,"tileentity",path,EntityType.TileEntity,null); mod.entityGroups.put(path,g);} }

    private static boolean safeEq(String a, String b) { return a==b || (a!=null && a.equals(b)); }
}
