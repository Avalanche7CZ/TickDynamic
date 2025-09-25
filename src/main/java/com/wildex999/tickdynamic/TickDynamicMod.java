package com.wildex999.tickdynamic;

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
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.wildex999.tickdynamic.commands.CommandHandler;
import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.listinject.EntityType;
import com.wildex999.tickdynamic.timemanager.ITimed;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedEntities;
import com.wildex999.tickdynamic.timemanager.TimedGroup;

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
    public static final String VERSION = "0.2.0-dev4";
    public static boolean debug = false;
    public static boolean debugGroups = false;
    public static boolean debugTimer = false;
    public static boolean debugProfiler = Boolean.getBoolean("tickdynamic.debugProfiler");
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

    public boolean versionCheckDone;
    VersionChecker versionChecker;

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
        versionChecker = new VersionChecker();
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
    }

    @Subscribe public void serverStart(FMLServerStartingEvent e) {
        e.registerServerCommand(new CommandHandler(this));
        tpsTimer.schedule(new TimerTickTask(this), 1000, 1000);
        versionCheckDone = false; versionChecker.runVersionCheck(); detectHybridEnvironment(); server = e.getServer();
        if(disableEntityTimeSlicing || safeEntityIteration) System.out.println("[TickDynamic] Entity time slicing disabled (" + (safeEntityIteration?"safe mode":"config") + ").");
    }

    @Subscribe public void serverStop(FMLServerStoppingEvent e) { tpsTimer.cancel(); server = null; }

    @SubscribeEvent(priority=EventPriority.HIGHEST) public void tickEventStart(ServerTickEvent e) {
        if(e.phase != TickEvent.Phase.START) return;
        if(autoEntitySafeTriggered && !safeEntityIteration) { safeEntityIteration = true; System.out.println("[TickDynamic] Safe entity iteration mode enforced after repeated fallbacks."); }
        if(!versionCheckDone) {
            VersionChecker.VersionData v = versionChecker.getVersionData();
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
        if(debugTimer) System.out.println("Tick time used: " + (root.getTimeUsed()/root.timeMilisecond) + "ms");
        root.balanceTime();
        updateTPS();
        if(saveConfig) { saveConfig = false; config.save(); }
    }

    public void updateTPS() {
        try { tpsMutex.acquire(); tickCounter++; double sum=0; for(int t : tpsList) sum+=t; if(!tpsList.isEmpty()) averageTPS = sum / tpsList.size(); tpsMutex.release(); } catch(InterruptedException ex) { ex.printStackTrace(); }
    }

    public TimedGroup getTimedGroup(String n) { return (TimedGroup)timedObjects.get(n); }
    public EntityGroup getEntityGroup(String n) { return entityGroups.get(n); }
    public TimeManager getTimeManager(String n) { return (TimeManager)timedObjects.get(n); }

    private String getEntityGroupName(World w, String n) {
        String remote = isRemote(w)?"client_":""; StringBuilder sb = new StringBuilder().append("worlds.").append(remote).append("dim").append(w.provider.dimensionId); if(n!=null && n.length()>0) sb.append('.').append(n); return sb.toString(); }

    public TimeManager getWorldTimeManager(World w) {
        String mn = getEntityGroupName(w,null); TimeManager m = getTimeManager(mn); if(m==null){ m=new TimeManager(this,w,mn,mn); m.init(); if(isRemote(w)) m.setSliceMax(0); config.setCategoryComment(mn,w.provider.getDimensionName()); root.addChild(m);} return m; }

    public TimedEntities getWorldTimedGroup(World w,String n,boolean canCreate,boolean hasConfig){ String gn=getEntityGroupName(w,n); TimedGroup g=getTimedGroup(gn); if((g==null || !(g instanceof TimedEntities)) && canCreate){ TimedGroup base=getTimedGroup("groups."+n); g=new TimedEntities(this,w,n,hasConfig?gn:null,base); g.init(); getWorldTimeManager(w).addChild(g);} return (TimedEntities)g; }

    public EntityGroup getWorldEntityGroup(World w,String n,EntityType t,boolean canCreate,boolean hasConfig){ String gn=getEntityGroupName(w,n); EntityGroup g=getEntityGroup(gn); if(g==null && canCreate){ EntityGroup base=getEntityGroup("groups."+n); g=new EntityGroup(this,w,getWorldTimedGroup(w,n,true,hasConfig),n,hasConfig?gn:null,t,base); entityGroups.put(gn,g);} return g; }

    public List<EntityGroup> getWorldEntityGroups(World w){ List<EntityGroup> list=new ArrayList<EntityGroup>(); int off=isRemote(w)?17:10; String pref=String.valueOf(w.provider.dimensionId)+"."; for(Map.Entry<String,EntityGroup> e: entityGroups.entrySet()){ String gn=e.getKey(); if(!gn.startsWith(pref,off)) continue; list.add(e.getValue()); } return list; }

    public void clearWorldEntityGroups(World w){ if(w==null) return; for(EntityGroup g: getWorldEntityGroups(w)){ if(g.getWorld()==null) continue; String gn=getEntityGroupName(g.getWorld(),g.getName()); entityGroups.remove(gn,g); g.valid=false;} }

    public String getWorldPrefix(World w){ return "worlds.dim"+w.provider.dimensionId; }
    public ConfigCategory getWorldConfigCategory(World w){ return config.getCategory(getWorldPrefix(w)); }
    public EntityGroup getWorldTileEntities(World w){ return getWorldEntityGroup(w,"tileentity",EntityType.TileEntity,true,true);}
    public EntityGroup getWorldEntities(World w){ return getWorldEntityGroup(w,"entity",EntityType.Entity,true,true);}

    public static boolean isRemote(World w){ if(w==null) return true; if(w instanceof net.minecraft.world.WorldServer) return false; try{ if(worldIsRemoteFieldPrimary==null && worldIsRemoteFieldFallback==null){ try{ worldIsRemoteFieldPrimary=World.class.getDeclaredField("isRemote"); worldIsRemoteFieldPrimary.setAccessible(true);}catch(NoSuchFieldException ex){ try{ worldIsRemoteFieldFallback=World.class.getDeclaredField("field_72995_K"); worldIsRemoteFieldFallback.setAccessible(true);}catch(NoSuchFieldException ignore){} } } if(worldIsRemoteFieldPrimary!=null) return worldIsRemoteFieldPrimary.getBoolean(w); if(worldIsRemoteFieldFallback!=null) return worldIsRemoteFieldFallback.getBoolean(w);}catch(IllegalAccessException ex){ if(debug) System.out.println("[TickDynamic] Failed reflective isRemote access: "+ex.getMessage()); } return !(w instanceof net.minecraft.world.WorldServer);}

    private void detectHybridEnvironment(){ if(hybridForced){ cauldronLike=true; System.out.println("[TickDynamic] Hybrid environment force-enabled via -Dtickdynamic.hybrid.force"); return;} boolean bukkit=classPresent("org.bukkit.Bukkit"); crucibleDetected=classPresent("crucible.Crucible")||classPresent("net.crucible.Crucible")||classPresent("net.minecraft.crucible.Crucible"); thermosDetected=classPresent("thermos.Thermos")||classPresent("org.thermosmc.Thermos"); kcauldronDetected=classPresent("kcauldron.KCauldron")||classPresent("net.minecraftforge.cauldron.CauldronHooks")||classPresent("net.minecraft.launchwrapper.KnotClassLoader"); cauldronLike=bukkit||crucibleDetected||thermosDetected||kcauldronDetected; if(cauldronLike){ StringBuilder sb=new StringBuilder("[TickDynamic] Detected hybrid server environment: "); if(crucibleDetected) sb.append("Crucible "); if(thermosDetected) sb.append("Thermos "); if(kcauldronDetected) sb.append("KCauldron "); if(!crucibleDetected && !thermosDetected && !kcauldronDetected && bukkit) sb.append("(Bukkit-based)"); System.out.println(sb.toString().trim()); } }

    private boolean classPresent(String n){ try{ Class.forName(n,false,getClass().getClassLoader()); return true;}catch(Throwable t){ return false;} }

    public boolean toggleIdentify(String player){ synchronized(identifyPlayers){ if(identifyPlayers.contains(player)){ identifyPlayers.remove(player); return false;} identifyPlayers.add(player); return true; } }
    public boolean setIdentify(String player, boolean enable){ synchronized(identifyPlayers){ if(enable) return identifyPlayers.add(player); else return identifyPlayers.remove(player);} }
    public boolean isIdentifying(String player){ synchronized(identifyPlayers){ return identifyPlayers.contains(player);} }
    public void clearIdentify(String player){ synchronized(identifyPlayers){ identifyPlayers.remove(player);} }
}
