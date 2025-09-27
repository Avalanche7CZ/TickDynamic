package com.wildex999.tickdynamic;

import net.minecraft.util.EnumChatFormatting; // added for color band method

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.wildex999.tickdynamic.commands.CommandHandler;
import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.listinject.EntityType;
import com.wildex999.tickdynamic.timemanager.ITimed;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedEntities;
import com.wildex999.tickdynamic.timemanager.TimedGroup;
import com.wildex999.tickdynamic.c2me.C2MEManager;
import com.wildex999.tickdynamic.web.WebServer;
import com.wildex999.tickdynamic.web.SnapshotProvider;
import com.wildex999.tickdynamic.util.MultiblockDetector;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent;

public class TickDynamicMod extends DummyModContainer {
    public static final String MODID = "tickDynamic";
    public static final String VERSION = "0.3.0";
    public static boolean debug = false;
    public static boolean debugGroups = false;
    public static boolean debugTimer = false;
    public static boolean debugProfiler = Boolean.getBoolean("tickdynamic.debugProfiler");
    // New optional tuning flags
    public static boolean entityExhaustLog = Boolean.getBoolean("tickdynamic.entity.logExhaust");
    public static double entityMinSliceTickMs = Double.parseDouble(System.getProperty("tickdynamic.entity.minSliceTickMs", "0"));
    private static Field worldIsRemoteFieldPrimary;
    private static Field worldIsRemoteFieldFallback;
    public static TickDynamicMod tickDynamic;
    public static boolean cauldronLike = false;
    public static boolean crucibleDetected = false;
    public static boolean thermosDetected = false;
    public static boolean kcauldronDetected = false;
    public static boolean hybridForced = Boolean.getBoolean("tickdynamic.hybrid.force");
    public static boolean safeEntityIteration = Boolean.getBoolean("tickdynamic.entity.safeMode");
    public static boolean disableTileEntityControl = Boolean.getBoolean("tickdynamic.tileentity.disable");
    public static boolean disableEntityTimeSlicing = Boolean.getBoolean("tickdynamic.entity.disableTimeSlicing");
    public static int autoSafeEntityThreshold = Integer.getInteger("tickdynamic.entity.autoSafeThreshold", 100);
    public static volatile boolean autoEntitySafeTriggered = false;
    public static int entityFallbackEvents = 0;

    // Offender-first TileEntity deprioritization (optional)
    public static boolean tileOffenderDeprioritize = Boolean.parseBoolean(System.getProperty("tickdynamic.tile.offender.deprioritize", "true"));
    public static int tileOffenderTop = Integer.getInteger("tickdynamic.tile.offender.top", 20);
    public static double tileOffenderMinMs = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.minMs", "2.0"));
    public static int tileOffenderSkipEvery = Integer.getInteger("tickdynamic.tile.offender.skipEvery", 3);
    // Graceful ramping config
    public double tileOffenderPenaltyUp = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.penaltyUp", "0.25"));
    public double tileOffenderPenaltyDown = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.penaltyDown", "0.05"));
    public double tileOffenderSkipMinPenalty = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.skipMinPenalty", "0.15"));
    // Player proximity bias (reduce skipping near players)
    public int tileOffenderNearPlayerRadius = Integer.getInteger("tickdynamic.tile.offender.nearPlayerRadius", 64);
    public double tileOffenderNearPlayerBias = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.nearPlayerBias", "0.4")); // multiply probability by this when near
    // Limit how many offenders per chunk are penalized (to avoid over-focusing one chunk)
    public int tileOffenderMaxPerChunk = Integer.getInteger("tickdynamic.tile.offender.maxPerChunk", 6);
    // Controller (health-based) scaling
    public double tileOffenderControllerGain = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.controller.gain", "0.1"));
    public double tileOffenderControllerMin = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.controller.min", "0.02"));
    public double tileOffenderControllerMax = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.controller.max", "0.5"));
    public double tileOffenderGlobalCapPercent = Double.parseDouble(System.getProperty("tickdynamic.tile.offender.globalCapPercent", "0.3"));
    // Exemption by class name regex (fully-qualified or simple)
    public String tileOffenderExcludeClassRegex = System.getProperty("tickdynamic.tile.offender.excludeClassRegex", "");
    public java.util.regex.Pattern tileOffenderExcludeClassPattern = (tileOffenderExcludeClassRegex==null||tileOffenderExcludeClassRegex.trim().isEmpty())?null:java.util.regex.Pattern.compile(tileOffenderExcludeClassRegex);

    public boolean enabled = true;

    public Map<String, ITimed> timedObjects;
    public Map<String, EntityGroup> entityGroups;
    public TimeManager root;
    public MinecraftServer server;
    public boolean saveConfig;
    public Configuration config;
    public WorldEventHandler eventHandler;
    private HashSet<String> identifyPlayers = new HashSet<String>();

    public int defaultTickTime = 50;
    public int defaultEntitySlicesMax = 100;
    public int defaultEntityMinimumObjects = 100;
    public float defaultEntityMinimumTPS = 0;
    public float defaultEntityMinimumTime = 0;
    public int defaultWorldSlicesMax = 100;
    public int defaultAverageTicks = 20;

    public java.util.concurrent.Semaphore tpsMutex;
    public java.util.Timer tpsTimer;
    public int tickCounter;
    public double averageTPS;
    public int tpsAverageSeconds = 5;
    public java.util.LinkedList<Integer> tpsList;
    public double lastTickDurationMs;

    public double activationTpsThreshold = 0.0; // normalized literal
    public boolean dynamicActive = true;
    public double activationTpsActivateBelow = 0.0; // hysteresis lower bound (activate when TPS below)
    public double activationTpsDeactivateAbove = 0.0; // hysteresis upper bound (deactivate when TPS above)
    public double colorHysteresisMarginPercent = 0.0; // margin to prevent color flicker in percent outputs
    public Map<String,Integer> colorBandCache = new HashMap<String,Integer>();

    // C2ME-lite settings
    public boolean c2meEnabled = false;
    public int c2mePrefetchRadius = 0;
    public int c2mePrefetchPerTick = 2;
    public double c2mePrefetchMinTps = 18.5; // normalized literal
    public boolean c2meDebug = false;
    public C2MEManager c2meManager;
    public String[] c2meDimensionWhitelist = new String[0];

    // Web dashboard settings
    public boolean webEnabled = Boolean.getBoolean("tickdynamic.web.enabled");
    public String webBind = System.getProperty("tickdynamic.web.bind", "127.0.0.1");
    public int webPort = Integer.getInteger("tickdynamic.web.port", 9777);
    public String webToken = System.getProperty("tickdynamic.web.token", "");
    public WebServer webServer;
    // Configurable snapshot cadence (ticks)
    public int webSnapshotEveryTicks = Integer.getInteger("tickdynamic.web.snapshotEveryTicks", 20);

    // Version check settings
    public boolean versionCheckEnabled = Boolean.parseBoolean(System.getProperty("tickdynamic.versionCheck.enabled", "false"));
    public VersionChecker versionChecker;
    public boolean versionCheckDone;

    // Isolated per-dimension balancing settings (offender-first shaping)
    public boolean isolatedMode = Boolean.parseBoolean(System.getProperty("tickdynamic.mode.isolated", "false"));
    public boolean isolatedSkipNoPlayers = Boolean.parseBoolean(System.getProperty("tickdynamic.isolated.skipNoPlayers", "true"));
    public int isolatedSinglePlayerMinPercent = Integer.getInteger("tickdynamic.isolated.singlePlayerMinPercent", 50); // floor for single-player worlds
    public Set<Integer> isolatedProtectedDims = parseIntSet(System.getProperty("tickdynamic.isolated.protectDims", "")); // e.g., "0" to protect overworld
    // Player-weighted fairness: weight world budgets by player count
    public boolean isolatedPlayerWeight = Boolean.parseBoolean(System.getProperty("tickdynamic.isolated.playerWeight", "true"));
    public double isolatedPlayerWeightScale = Double.parseDouble(System.getProperty("tickdynamic.isolated.playerWeight.scale", "0.25")); // weight = 1 + players*scale
    public double isolatedPlayerWeightMax = Double.parseDouble(System.getProperty("tickdynamic.isolated.playerWeight.max", "4.0")); // clamp
    // Optional reduced weight for worlds with no loaded tile entities
    public boolean isolatedLessIfNoTiles = Boolean.parseBoolean(System.getProperty("tickdynamic.isolated.lessIfNoTiles", "false"));
    public double isolatedNoTilesFactor = Double.parseDouble(System.getProperty("tickdynamic.isolated.noTilesFactor", "0.1")); // factor to reduce weight

    // Web/UI filtering and special world detection
    public Set<Integer> webIgnoreDims = parseIntSet(System.getProperty("tickdynamic.web.ignoreDims", ""));
    public String webIgnoreNameRegex = System.getProperty("tickdynamic.web.ignoreNameRegex", "");
    public Pattern webIgnoreNamePattern = (webIgnoreNameRegex==null||webIgnoreNameRegex.trim().isEmpty())?null:Pattern.compile(webIgnoreNameRegex, Pattern.CASE_INSENSITIVE);
    public double webMinHotspotMs = Double.parseDouble(System.getProperty("tickdynamic.web.minHotspotMs", "5"));
    // Optionally treat special worlds as protected in isolated balancing
    public boolean isolatedProtectSpecial = Boolean.parseBoolean(System.getProperty("tickdynamic.isolated.protectSpecial", "true"));

    public TickDynamicMod() {
        super(new cpw.mods.fml.common.ModMetadata());
        cpw.mods.fml.common.ModMetadata meta = getMetadata();
        meta.version = VERSION;
        meta.modId = MODID;
        meta.name = "Tick Dynamic";
        meta.authorList.add("Wildex999 ( wildex999@gmail.com )");
        meta.description = "Dynamic control of the world tickrate to reduce apparent lag.";
        meta.updateUrl = "http://mods.stjerncraft.com/tickdynamic";
        meta.url = "http://mods.stjerncraft.com/tickdynamic";
        tickDynamic = this;
        tpsMutex = new java.util.concurrent.Semaphore(1);
        tpsTimer = new java.util.Timer();
        tpsList = new java.util.LinkedList<Integer>();
        // Create only when enabled later (after config load), keep null here
        versionChecker = null;
    }

    @Override public boolean registerBus(EventBus bus, LoadController controller) { bus.register(this); return true; }

    @Subscribe public void preInit(FMLPreInitializationEvent e) { config = new Configuration(e.getSuggestedConfigurationFile()); }

    public void loadConfig(boolean groups) { TickDynamicConfig.loadConfig(this, groups); }
    public void queueSaveConfig() { saveConfig = true; }

    @Subscribe public void init(FMLInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(this);
        timedObjects = new HashMap<String, ITimed>();
        entityGroups = new HashMap<String, EntityGroup>();
        loadConfig(true);
        root = new TimeManager(this, null, "root", null); root.init(); root.setTimeMax(defaultTickTime * TimeManager.timeMilisecond);
        TimedGroup other = new TimedGroup(this, null, "other", "other"); other.setSliceMax(0); root.addChild(other);
        TimedGroup external = new TimedGroup(this, null, "external", "external"); external.setSliceMax(0); root.addChild(external);
        WorldEventHandler handler = new WorldEventHandler(this); this.eventHandler = handler; MinecraftForge.EVENT_BUS.register(handler); FMLCommonHandler.instance().bus().register(handler);
        MinecraftForge.EVENT_BUS.register(new IdentifyEventHandler(this));
        boolean hysteresisConfigured = activationTpsActivateBelow > 0 && activationTpsDeactivateAbove > activationTpsActivateBelow;
        // If starting inactive in either legacy threshold mode or hysteresis mode, set unlimited budgets
        if(!dynamicActive && (activationTpsThreshold > 0 || hysteresisConfigured)) setAllUnlimited(root);
        if(c2meEnabled) c2meManager = new C2MEManager(this);
        // Mode summary log
        if(hysteresisConfigured) {
            System.out.println("[TickDynamic] Hysteresis mode: activateBelow="+activationTpsActivateBelow+" deactivateAbove="+activationTpsDeactivateAbove+" startingActive="+dynamicActive);
        } else if(activationTpsThreshold > 0) {
            System.out.println("[TickDynamic] Threshold mode: threshold="+activationTpsThreshold+" startingActive="+dynamicActive+" (suppressed initial evaluation until TPS ready)");
        } else {
            System.out.println("[TickDynamic] Always-active mode (no threshold); dynamic balancing starts active.");
        }
        if(webEnabled) {
            System.out.println("[TickDynamic][Web] Dashboard enabled on http://"+webBind+":"+webPort+" (token " + (webToken.isEmpty()?"disabled":"enabled") + ")");
        }
    }

    @Subscribe public void serverStart(FMLServerStartingEvent e) {
        e.registerServerCommand(new CommandHandler(this));
        tpsTimer.schedule(new TimerTickTask(this), 1000, 1000);
        versionCheckDone = false;
        // Start version check only if enabled
        if(versionCheckEnabled) {
            if(versionChecker == null) versionChecker = new VersionChecker();
            versionChecker.runVersionCheck();
        } else {
            versionCheckDone = true;
        }
        detectHybridEnvironment(); server = e.getServer();
        // Cache the server tick thread for fast checks
        try { com.wildex999.tickdynamic.listinject.ListManager.SERVER_THREAD = Thread.currentThread(); } catch(Throwable ignore) {}
        if(disableEntityTimeSlicing || safeEntityIteration) System.out.println("[TickDynamic] Entity time slicing disabled (" + (safeEntityIteration?"safe mode":"config") + ").");
        if(c2meEnabled) System.out.println("[TickDynamic][C2ME] Prefetch enabled: radius="+c2mePrefetchRadius+" perTick="+c2mePrefetchPerTick+" minTps="+c2mePrefetchMinTps);
        if(webEnabled) {
            try {
                webServer = new WebServer(this, webBind, webPort, webToken);
                webServer.start();
            } catch(Exception ex) {
                System.err.println("[TickDynamic][Web] Failed to start web dashboard: " + ex.getMessage());
                ex.printStackTrace();
                webServer = null;
            }
        }
    }

    @Subscribe public void serverStop(FMLServerStoppingEvent e) {
        tpsTimer.cancel(); server = null; if(c2meManager!=null) c2meManager.shutdown();
        if(webServer != null) { try { webServer.stop(); } catch(Exception ignore) {} webServer = null; }
        // Clear cached server thread
        try { com.wildex999.tickdynamic.listinject.ListManager.SERVER_THREAD = null; } catch(Throwable ignore) {}
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST) public void tickEventStart(ServerTickEvent e) {
        if(e.phase != TickEvent.Phase.START) return;

        // Multiblock detection cleanup (lightweight)
        MultiblockDetector.cleanup();

        if(autoEntitySafeTriggered && !safeEntityIteration) { safeEntityIteration = true; System.out.println("[TickDynamic] Safe entity iteration mode enforced after repeated fallbacks."); }
        if(versionCheckEnabled && !versionCheckDone) {
            VersionChecker.VersionData v = versionChecker != null ? versionChecker.getVersionData() : null;
            if(v != null) { versionCheckDone = true; if(v.checkOk) System.out.println("TickDynamic version check: Latest="+v.modVersion+" URL: http://"+v.updateUrl); else System.out.println("TickDynamic version check: Error while checking latest version!"); }
        }
        TimedGroup external = getTimedGroup("external");
        external.endTimer();
        long ms = 50;
        long overTime = external.getTimeUsed() - (ms*external.timeMilisecond);
        long overTimeTick = (ms*external.timeMilisecond) - (root.getTimeUsed() - external.getTimeUsed());
        if(overTimeTick < 0) overTime += overTimeTick;
        external.setTimeUsed(Math.max(0, overTime));
        external.startTimer();
        root.newTick(true);
        getTimedGroup("other").startTimer();
    }

    @SubscribeEvent(priority=EventPriority.LOWEST) public void tickEventEnd(ServerTickEvent e) {
        if(e.phase != TickEvent.Phase.END) return;
        getTimedGroup("other").endTimer();
        root.endTick(true);
        lastTickDurationMs = (root.getTimeUsed()/ (double)TimeManager.timeMilisecond);
        if(debugTimer) System.out.println("Tick time used: " + lastTickDurationMs + "ms");
        try { com.wildex999.tickdynamic.listinject.CustomProfiler.lagIndex.decay(); } catch(Throwable ignore) {}
        boolean tpsReady = !tpsList.isEmpty();
        boolean hysteresis = activationTpsActivateBelow > 0 && activationTpsDeactivateAbove > activationTpsActivateBelow;
        if(hysteresis) {
            if(dynamicActive) {
                if(tpsReady && averageTPS > activationTpsDeactivateAbove) {
                    dynamicActive = false;
                    System.out.println("[TickDynamic] Deactivating (hysteresis) dynamic balancing TPS="+String.format("%.2f", averageTPS)+" > " + activationTpsDeactivateAbove);
                    setAllUnlimited(root);
                } else {
                    if(isolatedMode) balanceWorldsIsolated(); else root.balanceTime();
                }
            } else {
                if(tpsReady && averageTPS < activationTpsActivateBelow) {
                    dynamicActive = true;
                    System.out.println("[TickDynamic] Activating (hysteresis) dynamic balancing TPS="+String.format("%.2f", averageTPS)+" < " + activationTpsActivateBelow);
                    root.setTimeMax(defaultTickTime * TimeManager.timeMilisecond);
                    if(isolatedMode) balanceWorldsIsolated(); else root.balanceTime();
                }
            }
        } else {
            if(activationTpsThreshold <= 0) {
                if(isolatedMode) balanceWorldsIsolated(); else root.balanceTime();
            } else {
                boolean shouldBeActive = tpsReady && averageTPS < activationTpsThreshold;
                if(shouldBeActive) {
                    if(!dynamicActive) {
                        dynamicActive = true;
                        System.out.println("[TickDynamic] Activating dynamic balancing (TPS=" + String.format("%.2f", averageTPS) + ", threshold=" + activationTpsThreshold + ")");
                        root.setTimeMax(defaultTickTime * TimeManager.timeMilisecond);
                    }
                    if(isolatedMode) balanceWorldsIsolated(); else root.balanceTime();
                } else if(dynamicActive && tpsReady) {
                    dynamicActive = false;
                    System.out.println("[TickDynamic] Deactivating dynamic balancing (TPS=" + String.format("%.2f", averageTPS) + ", threshold=" + activationTpsThreshold + ") – unlimited mode");
                    setAllUnlimited(root);
                } else if(dynamicActive && !tpsReady) {
                    if(isolatedMode) balanceWorldsIsolated(); else root.balanceTime();
                }
            }
        }
        if(c2meEnabled && c2meManager != null) c2meManager.tick();
        if(webServer != null) {
            try {
                int every = Math.max(1, webSnapshotEveryTicks);
                if((tickCounter % every) == 0) {
                    String json = SnapshotProvider.buildJson(this);
                    webServer.updateSnapshot(json);
                }
            } catch(Throwable t) {
                if(debug) System.out.println("[TickDynamic][Web] Snapshot update failed: " + t.getMessage());
            }
        }
        try {
            if(tileOffenderDeprioritize && server != null && server.worldServers != null) {
                long thrNs = (long)(tileOffenderMinMs * com.wildex999.tickdynamic.timemanager.ITimed.timeMilisecond);
                long budgetNs = (long) (defaultTickTime * com.wildex999.tickdynamic.timemanager.ITimed.timeMilisecond);
                double errorMs = Math.max(0.0, lastTickDurationMs - defaultTickTime);
                double baseGain = (errorMs <= 0.0 || !dynamicActive || !tpsReady) ? 0.0 : (tileOffenderControllerGain * (errorMs / Math.max(1.0, defaultTickTime)));
                if(baseGain > 0.0) {
                    if(baseGain < tileOffenderControllerMin) baseGain = tileOffenderControllerMin;
                    if(baseGain > tileOffenderControllerMax) baseGain = tileOffenderControllerMax;
                }
                for(net.minecraft.world.WorldServer ws : server.worldServers) {
                    if(ws == null) continue;
                    int dim = ws.provider.dimensionId;
                    java.util.List<com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.TileStat> top = com.wildex999.tickdynamic.listinject.CustomProfiler.lagIndex.snapshotTopTiles(dim, Math.max(1, tileOffenderTop));
                    java.util.HashSet<Long> set = new java.util.HashSet<Long>();
                    java.util.HashMap<Long, Double> sevMap = new java.util.HashMap<Long, Double>();
                    java.util.HashMap<Long, Integer> perChunk = new java.util.HashMap<Long, Integer>();
                    for(com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.TileStat ts : top) {
                        if(ts.totalNs < thrNs) continue;
                        boolean exempt = false;
                        try {
                            net.minecraft.tileentity.TileEntity te = ws.getTileEntity(ts.x, ts.y, ts.z);
                            if(te != null && tileOffenderExcludeClassPattern != null) {
                                String cn = te.getClass().getName();
                                String sn = te.getClass().getSimpleName();
                                if(tileOffenderExcludeClassPattern.matcher(cn).find() || tileOffenderExcludeClassPattern.matcher(sn).find()) exempt = true;
                            }
                        } catch(Throwable ignore) {}
                        if(exempt) continue;
                        int cx = ts.x >> 4, cz = ts.z >> 4; long ck = (((long)cx) << 32) ^ (cz & 0xffffffffL);
                        int cnt = perChunk.getOrDefault(ck, 0);
                        if(cnt >= Math.max(1, tileOffenderMaxPerChunk)) continue;
                        perChunk.put(ck, cnt+1);
                        long key = packTileKey(ts.x, ts.y, ts.z);
                        set.add(key);
                        // Severity by time intensity relative to half-budget (>=50% budget => sev ~ 1.0)
                        double sev = ts.totalNs / (double)(Math.max(1L, budgetNs/2));
                        if(sev > 1.0) sev = 1.0; if(sev < 0) sev = 0;
                        sevMap.put(key, Double.valueOf(sev));
                    }
                    if(!set.isEmpty()) tileOffenders.put(Integer.valueOf(dim), set); else tileOffenders.remove(Integer.valueOf(dim));
                    // Graceful penalty update per dimension
                    java.util.concurrent.ConcurrentHashMap<Long, Double> pen = tileOffenderPenalty.get(Integer.valueOf(dim));
                    if(pen == null) { pen = new java.util.concurrent.ConcurrentHashMap<Long, Double>(); tileOffenderPenalty.put(Integer.valueOf(dim), pen); }
                    java.util.concurrent.ConcurrentHashMap<Long, Double> lastSev = tileOffenderSeverity.get(Integer.valueOf(dim));
                    if(lastSev == null) { lastSev = new java.util.concurrent.ConcurrentHashMap<Long, Double>(); tileOffenderSeverity.put(Integer.valueOf(dim), lastSev); }
                    // Decay all existing penalties
                    for(java.util.Iterator<java.util.Map.Entry<Long, Double>> it = pen.entrySet().iterator(); it.hasNext();) {
                        java.util.Map.Entry<Long, Double> en = it.next();
                        double p = en.getValue() != null ? en.getValue().doubleValue() : 0.0;
                        p -= tileOffenderPenaltyDown; if(p < 0) p = 0;
                        if(p < 0.01 && !set.contains(en.getKey())) { it.remove(); } else { en.setValue(Double.valueOf(p)); }
                    }
                    // Global cap scaling by percent of loaded TE
                    int loadedTe = 0; try { java.util.List<?> raw = ws.loadedTileEntityList; if(raw != null) loadedTe = raw.size(); } catch(Throwable ignore) {}
                    int maxPen = (int)Math.floor(Math.max(0.0, tileOffenderGlobalCapPercent) * Math.max(1, loadedTe));
                    double gainScale = 1.0;
                    if(maxPen > 0) {
                        int currentPenalized = pen.size();
                        if(currentPenalized > maxPen) gainScale = Math.max(0.1, (double)maxPen / (double)currentPenalized);
                    }
                    double effGain = baseGain * gainScale;
                    // Boost penalties for current offenders proportionally to severity and health gain
                    if(effGain > 0.0) {
                        for(java.util.Map.Entry<Long, Double> en : sevMap.entrySet()) {
                            long k = en.getKey(); double severity = en.getValue() != null ? en.getValue().doubleValue() : 0.0;
                            Double prev = pen.get(k); double p = prev == null ? 0.0 : prev.doubleValue();
                            double dp = tileOffenderPenaltyUp * severity * effGain;
                            if(dp > 0.2) dp = 0.2; // slew limit per tick
                            p += dp;
                            if(p > 1.0) p = 1.0;
                            pen.put(k, Double.valueOf(p));
                        }
                    }
                    // Store last severities for snapshot/observability
                    lastSev.clear();
                    for(java.util.Map.Entry<Long, Double> en : sevMap.entrySet()) lastSev.put(en.getKey(), en.getValue());
                }
            }
        } catch(Throwable ignore) {}
        updateTPS();
        if(saveConfig) { saveConfig = false; config.save(); }
    }

    // Hash-like pack of coordinates for offender lookup
    public static long packTileKey(int x,int y,int z){ return (((long)x) * 73856093L) ^ (((long)y) * 19349663L) ^ (((long)z) * 83492791L); }

    // Map of offender keys per dimension (when enabled)
    public final java.util.concurrent.ConcurrentHashMap<Integer, java.util.Set<Long>> tileOffenders = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.Set<Long>>();
    // Per-dimension penalty map for graceful skip probability [0..1]
    public final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentHashMap<Long, Double>> tileOffenderPenalty = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentHashMap<Long, Double>>();
    // Per-dimension last computed severity per offender (0..1)
    public final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentHashMap<Long, Double>> tileOffenderSeverity = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentHashMap<Long, Double>>();

    // Helper: parse CSV of ints into a set
    private static Set<Integer> parseIntSet(String csv) {
        Set<Integer> set = new java.util.HashSet<Integer>();
        if(csv == null || csv.trim().isEmpty()) return set;
        for(String s : csv.split(",")) {
            try { set.add(Integer.parseInt(s.trim())); } catch(NumberFormatException ignore) {}
        }
        return set;
    }

    // Recursively mark a timed tree as unlimited
    private void setAllUnlimited(ITimed timed) {
        long unlimited = Long.MAX_VALUE / 4L;
        timed.setTimeMax(unlimited);
        if(timed instanceof com.wildex999.tickdynamic.timemanager.TimeManager) {
            com.wildex999.tickdynamic.timemanager.TimeManager tm = (com.wildex999.tickdynamic.timemanager.TimeManager)timed;
            for(ITimed child : tm.getChildren()) setAllUnlimited(child);
        }
    }

    // Light wrappers to access maps safely
    public TimedGroup getTimedGroup(String n) { return (TimedGroup) timedObjects.get(n); }
    public EntityGroup getEntityGroup(String n) { return entityGroups.get(n); }
    public com.wildex999.tickdynamic.timemanager.TimeManager getTimeManager(String n) { return (com.wildex999.tickdynamic.timemanager.TimeManager) timedObjects.get(n); }

    // Update moving-average TPS
    public void updateTPS() {
        try {
            tpsMutex.acquire();
            tickCounter++;
            double sum = 0;
            for(int t : tpsList) sum += t;
            if(!tpsList.isEmpty()) averageTPS = sum / tpsList.size();
            tpsMutex.release();
        } catch(InterruptedException ex) { ex.printStackTrace(); }
    }

    // Environment detection (stubbed to avoid compile errors if platforms not present)
    private void detectHybridEnvironment(){ /* no-op for now */ }

    // Isolated balancing fallback (simple): defer to legacy balancing
    private void balanceWorldsIsolated() {
        try {
            if(server == null || server.worldServers == null) { root.balanceTime(); return; }
            // Build weights per world
            java.util.ArrayList<net.minecraft.world.WorldServer> worlds = new java.util.ArrayList<net.minecraft.world.WorldServer>();
            for(net.minecraft.world.WorldServer ws : server.worldServers) if(ws != null) worlds.add(ws);
            if(worlds.isEmpty()) { root.balanceTime(); return; }
            double sumW = 0.0;
            java.util.HashMap<Integer, Double> weights = new java.util.HashMap<Integer, Double>();
            for(net.minecraft.world.WorldServer w : worlds) {
                int dim = w.provider.dimensionId;
                // base weight
                double wgt = 1.0;
                // player-weighted fairness
                if(isolatedPlayerWeight) {
                    int players = 0; try { players = (w.playerEntities != null) ? w.playerEntities.size() : 0; } catch(Throwable ignore) {}
                    double mult = 1.0 + (players * isolatedPlayerWeightScale);
                    if(mult > isolatedPlayerWeightMax) mult = isolatedPlayerWeightMax;
                    if(mult < 1.0) mult = 1.0; // never below base due to players
                    wgt *= mult;
                    if(isolatedSkipNoPlayers && players == 0) wgt = 0.0;
                }
                // Optional reduction if no tile entities are loaded
                if(isolatedLessIfNoTiles && wgt > 0.0) {
                    int loadedTe = 0; try { java.util.List<?> lt = w.loadedTileEntityList; if(lt != null) loadedTe = lt.size(); } catch(Throwable ignore) {}
                    if(loadedTe == 0) {
                        double f = isolatedNoTilesFactor; if(f < 0.0) f = 0.0; if(f > 1.0) f = 1.0;
                        wgt *= f;
                    }
                }
                boolean prot = (isolatedProtectedDims != null && isolatedProtectedDims.contains(Integer.valueOf(dim))) || (isolatedProtectSpecial && isSpecialWorld(w));
                if(prot && wgt < 1.0) wgt = 1.0; // minimum floor for protected worlds
                weights.put(Integer.valueOf(dim), Double.valueOf(wgt));
                sumW += wgt;
            }
            // If sum is zero (all skipped), fall back to equal sharing
            if(sumW <= 0.0) {
                sumW = worlds.size();
                for(net.minecraft.world.WorldServer w : worlds) weights.put(Integer.valueOf(w.provider.dimensionId), Double.valueOf(1.0));
            }
            // Assign slices proportional to weights
            final int BASE_SLICES = 1000;
            for(net.minecraft.world.WorldServer w : worlds) {
                int dim = w.provider.dimensionId;
                com.wildex999.tickdynamic.timemanager.TimeManager tm = getWorldTimeManager(w);
                double wgt = weights.get(Integer.valueOf(dim)).doubleValue();
                int slices;
                if(wgt <= 0.0) {
                    slices = 0; // skip world with no players (unless protected floor applied above)
                } else {
                    slices = (int)Math.round((wgt / sumW) * BASE_SLICES);
                    if(slices <= 0) slices = 1;
                }
                tm.setSliceMax(slices);
            }
            // Ensure root budget is the default tick time while active
            if(dynamicActive) root.setTimeMax(defaultTickTime * com.wildex999.tickdynamic.timemanager.TimeManager.timeMilisecond);
            root.balanceTime();
        } catch(Throwable ignore) {
            try { root.balanceTime(); } catch(Throwable ignored) {}
        }
    }

    // ===== Added helpers and integration APIs =====
    // Reflection-friendly remote check to handle obfuscated forks
    public static boolean isRemote(World w) {
        if(w == null) return true;
        try { return w.isRemote; } catch(Throwable ignored) {}
        try {
            if(worldIsRemoteFieldPrimary == null) {
                try { worldIsRemoteFieldPrimary = World.class.getDeclaredField("isRemote"); worldIsRemoteFieldPrimary.setAccessible(true); }
                catch(NoSuchFieldException e){
                    try { worldIsRemoteFieldFallback = World.class.getDeclaredField("field_72995_K"); worldIsRemoteFieldFallback.setAccessible(true); } catch(NoSuchFieldException ex) {}
                }
            }
            Field f = worldIsRemoteFieldPrimary != null ? worldIsRemoteFieldPrimary : worldIsRemoteFieldFallback;
            if(f != null) return f.getBoolean(w);
        } catch(Throwable ignored) {}
        // Safe default if unknown: assume server side
        return false;
    }

    // Get the config category for a specific world (creates if missing)
    public ConfigCategory getWorldConfigCategory(World w) {
        int dim = w.provider.dimensionId;
        String path = worldKey(dim);
        if(!config.hasCategory(path)) config.get(path, ITimed.configKeySlicesMax, defaultWorldSlicesMax);
        return config.getCategory(path);
    }

    // Get or create the per-world TimeManager under root
    public TimeManager getWorldTimeManager(World w) {
        if(w == null) return null;
        int dim = w.provider.dimensionId;
        String path = worldKey(dim);
        // Try by config entry first
        TimeManager tm = (TimeManager) timedObjects.get(path);
        if(tm == null) {
            // Also try by name (defensive) in case older entries used the name only
            tm = (TimeManager) timedObjects.get(path);
        }
        if(tm == null) {
            tm = new TimeManager(this, w, path, path);
            tm.init();
            tm.setSliceMax(defaultWorldSlicesMax);
            tm.setTimeMax(defaultTickTime * TimeManager.timeMilisecond);
            if(root != null) root.addChild(tm);
        }
        return tm;
    }

    // Get or create the per-world TimedEntities for a named group (entity or tileentity)
    public TimedEntities getWorldTimedGroup(World w, String name, boolean create, boolean createConfigEntry) {
        if(w == null || name == null) return null;
        int dim = w.provider.dimensionId;
        String path = worldKey(dim) + "." + name;
        ITimed it = timedObjects.get(path);
        TimedEntities tg = (it instanceof TimedEntities) ? (TimedEntities)it : null;
        if(tg == null && create) {
            // Use unique name to avoid collisions; only set configEntry if requested
            String uniqueName = path;
            String cfg = createConfigEntry ? path : null;
            tg = new TimedEntities(this, w, uniqueName, cfg, null);
            tg.init();
            // Inherit slices/min from global base if available
            EntityGroup base = getEntityGroup("groups." + name);
            if(base != null && base.getTimedGroup() != null) {
                tg.setSliceMax(base.getTimedGroup().getSliceMax());
                if(base.getTimedGroup() instanceof TimedEntities) {
                    TimedEntities be = (TimedEntities) base.getTimedGroup();
                    tg.setMinimumObjects(be.getMinimumObjects());
                    tg.setMinimumTPS(be.getMinimumTPS());
                    tg.setMinimumTime(be.getMinimumTime());
                }
            } else {
                tg.setSliceMax(defaultEntitySlicesMax);
            }
            TimeManager worldTm = getWorldTimeManager(w);
            if(worldTm != null) worldTm.addChild(tg);
        }
        return tg;
    }

    // Get or create the per-world EntityGroup backing a named group
    public EntityGroup getWorldEntityGroup(World w, String name, EntityType type, boolean create, boolean createConfigEntry) {
        if(w == null || name == null) return null;
        int dim = w.provider.dimensionId;
        String key = worldKey(dim) + "." + name;
        EntityGroup eg = entityGroups.get(key);
        if(eg == null && create) {
            // Determine base group from global definitions (inherit entries/behavior)
            EntityGroup base = getEntityGroup("groups." + name);
            TimedEntities tg = getWorldTimedGroup(w, name, true, createConfigEntry);
            String cfg = createConfigEntry ? key : null;
            // When no base exists, create a neutral base timed group to anchor
            if(base == null) {
                TimedEntities baseTg = (TimedEntities) getTimedGroup("groups." + name);
                if(baseTg == null) {
                    baseTg = new TimedEntities(this, null, "groups." + name, "groups." + name, null);
                    baseTg.init();
                }
                base = new EntityGroup(this, null, baseTg, name, "groups." + name, type, null);
                base.valid = true;
                entityGroups.put("groups." + name, base);
            }
            eg = new EntityGroup(this, w, tg, name, cfg, type, base);
            entityGroups.put(key, eg);
        }
        return eg;
    }

    // Return all EntityGroups for a given world
    public java.util.List<EntityGroup> getWorldEntityGroups(World w) {
        java.util.ArrayList<EntityGroup> out = new java.util.ArrayList<EntityGroup>();
        if(w == null) return out;
        String prefix = worldKey(w.provider.dimensionId) + ".";
        for(Map.Entry<String, EntityGroup> e : entityGroups.entrySet()) {
            if(e.getKey().startsWith(prefix)) out.add(e.getValue());
        }
        // Ensure base groups exist (entity/tileentity) if none yet
        if(out.isEmpty()) {
            EntityGroup eg = getWorldEntityGroup(w, "entity", EntityType.Entity, true, false);
            if(eg != null) out.add(eg);
            if(!disableTileEntityControl) {
                EntityGroup tg = getWorldEntityGroup(w, "tileentity", EntityType.TileEntity, true, false);
                if(tg != null) out.add(tg);
            }
        }
        return out;
    }

    // Clear all per-world groups and timed objects for a world
    public void clearWorldEntityGroups(World w) {
        if(w == null) return;
        String prefix = worldKey(w.provider.dimensionId);
        // Remove entity groups
        java.util.ArrayList<String> toRemove = new java.util.ArrayList<String>();
        for(Map.Entry<String, EntityGroup> e : entityGroups.entrySet()) {
            if(e.getKey().startsWith(prefix)) toRemove.add(e.getKey());
        }
        for(String k : toRemove) {
            EntityGroup g = entityGroups.remove(k);
            if(g != null) g.valid = false;
        }
        // Remove timed objects (groups and manager)
        java.util.ArrayList<String> timedKeys = new java.util.ArrayList<String>();
        for(Map.Entry<String, ITimed> e : timedObjects.entrySet()) {
            if(e.getKey().startsWith(prefix)) timedKeys.add(e.getKey());
        }
        for(String k : timedKeys) timedObjects.remove(k);
    }

    // Simple color banding with hysteresis and caching to reduce flicker
    public EnumChatFormatting getColorBand(String key, double percent) {
        if(percent < 0) percent = 0; if(percent > 100) percent = 100;
        int prev = colorBandCache.containsKey(key) ? colorBandCache.get(key) : -1;
        // thresholds: 95=GREEN, 85=YELLOW, 70=GOLD, else RED
        double m = colorHysteresisMarginPercent <= 0 ? 0 : colorHysteresisMarginPercent;
        int band;
        if(prev == 3) {
            // was RED; require crossing GOLD threshold + margin to upgrade
            if(percent >= 70 + m) band = 2; else band = 3;
        } else if(prev == 2) {
            // was GOLD; degrade if below 70-m; upgrade if >=85+m
            if(percent < 70 - m) band = 3; else if(percent >= 85 + m) band = 1; else band = 2;
        } else if(prev == 1) {
            // was YELLOW; degrade if <85-m; upgrade if >=95+m
            if(percent < 85 - m) band = 2; else if(percent >= 95 + m) band = 0; else band = 1;
        } else if(prev == 0) {
            // was GREEN; degrade only if <95-m
            if(percent < 95 - m) band = 1; else band = 0;
        } else {
            // no previous; pick fresh
            if(percent >= 95) band = 0; else if(percent >= 85) band = 1; else if(percent >= 70) band = 2; else band = 3;
        }
        colorBandCache.put(key, band);
        switch(band) {
            case 0: return EnumChatFormatting.GREEN;
            case 1: return EnumChatFormatting.YELLOW;
            case 2: return EnumChatFormatting.GOLD;
            default: return EnumChatFormatting.RED;
        }
    }

    // Identify-mode helpers used by commands and event handler
    public void setIdentify(String player, boolean on) {
        if(player == null) return; if(on) identifyPlayers.add(player); else identifyPlayers.remove(player);
    }
    public void clearIdentify(String player) { if(player != null) identifyPlayers.remove(player); }
    public boolean isIdentifying(String player) { return player != null && identifyPlayers.contains(player); }
    public boolean toggleIdentify(String player) { if(player == null) return false; if(identifyPlayers.contains(player)){ identifyPlayers.remove(player); return false; } else { identifyPlayers.add(player); return true; } }

    // Mark a world as special for web filtering and isolated-protection purposes
    public boolean isSpecialWorld(World w) {
        if(w == null) return false;
        int dim = w.provider.dimensionId;
        if(webIgnoreDims != null && webIgnoreDims.contains(Integer.valueOf(dim))) return true;
        try {
            String name = w.provider.getDimensionName();
            if(webIgnoreNamePattern != null && name != null && webIgnoreNamePattern.matcher(name).find()) return true;
        } catch(Throwable ignored) {}
        return false;
    }

    private static String worldKey(int dim) { return "worlds.dim" + dim; }
}
