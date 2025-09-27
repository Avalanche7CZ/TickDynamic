package com.wildex999.tickdynamic.commands;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.wildex999.tickdynamic.TickDynamicMod;
import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.listinject.EntityType;
import com.wildex999.tickdynamic.listinject.ListManager;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedEntities;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraft.world.WorldServer;

public class CommandWorld implements ICommand {

    private final TickDynamicMod mod;
    private World world;
    private int rowsPerPage = 10;
    private int currentPage = 1;
    private int maxPages = 1;

    private final DecimalFormat msFmt = new DecimalFormat("#.00");
    private final DecimalFormat pctFmt = new DecimalFormat("#.00");

    public CommandWorld(TickDynamicMod mod) { this.mod = mod; }

    @Override public String getCommandName() { return "tickdynamic world"; }
    @Override public String getCommandUsage(ICommandSender sender) { return "tickdynamic world <dim|dimX> [page]"; }
    @Override public List getCommandAliases() { return null; }

    @Override public void processCommand(ICommandSender sender, String[] args) {
        if(args.length <= 1){ sender.addChatMessage(new ChatComponentText("Usage: "+getCommandUsage(sender))); return; }
        // Parse dimension id
        String dimStr = args[1];
        if(dimStr.startsWith("dim")) dimStr = dimStr.substring(3);
        int dimId;
        try { dimId = Integer.parseInt(dimStr); } catch(NumberFormatException ex){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"Invalid dimension: "+args[1])); return; }
        if(args.length >=3){ try { currentPage = Integer.parseInt(args[2]); if(currentPage<1) currentPage=1; } catch(NumberFormatException ex){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"Invalid page: "+args[2])); return; } }
        world = DimensionManager.getWorld(dimId);
        if(world == null){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"World not loaded for dim "+dimId)); return; }
        TimeManager wm = mod.getWorldTimeManager(world);
        long worldUsedNs = wm.getTimeUsedAverage();
        long worldAllocNs = wm.getTimeMax();
        double usedMs = worldUsedNs/(double)TimeManager.timeMilisecond;
        double allocMs = worldAllocNs/(double)TimeManager.timeMilisecond;
        boolean unlimited = !mod.dynamicActive || worldAllocNs > (mod.defaultTickTime * TimeManager.timeMilisecond * 10L);
        double pctAlloc = unlimited ? (mod.defaultTickTime>0? usedMs*100.0/mod.defaultTickTime:0) : (allocMs>0? usedMs*100.0/allocMs:0);

        // Collect groups
        List<EntityGroup> groups = new ArrayList<EntityGroup>();
        if(world.loadedEntityList instanceof ListManager) addGroups(groups,(ListManager)world.loadedEntityList);
        if(world.loadedTileEntityList instanceof ListManager) addGroups(groups,(ListManager)world.loadedTileEntityList);
        Collections.sort(groups, new Comparator<EntityGroup>(){ public int compare(EntityGroup a, EntityGroup b){ return a.getName().compareToIgnoreCase(b.getName()); }});

        maxPages = Math.max(1,(int)Math.ceil(groups.size()/(double)rowsPerPage)); if(currentPage>maxPages) currentPage=maxPages;
        int startIndex = (currentPage-1)*rowsPerPage;

        // Aggregate stats
        long entNs=0,tileNs=0; int entObjs=0,tileObjs=0; int entGroups=0,tileGroups=0;
        for(EntityGroup g: groups){ TimedEntities tg = g.getTimedGroup(); if(tg==null) continue; long u=tg.getTimeUsedAverage(); if(g.getGroupType()==EntityType.Entity){ entNs+=u; entGroups++; entObjs+=g.entities.size(); } else { tileNs+=u; tileGroups++; tileObjs+=g.entities.size(); } }
        double entMs=entNs/(double)TimeManager.timeMilisecond; double tileMs=tileNs/(double)TimeManager.timeMilisecond;
        double entPctWorld = usedMs>0? entMs*100.0/Math.max(usedMs,0.0001):0; double tilePctWorld = usedMs>0? tileMs*100.0/Math.max(usedMs,0.0001):0;

        // Header
        sendHeader(sender, wm, usedMs, allocMs, pctAlloc, unlimited, entMs, tileMs, entGroups, tileGroups, entObjs, tileObjs);
        // Lines
        for(int i=startIndex;i<groups.size() && i<startIndex+rowsPerPage;i++) writeGroup(sender, groups.get(i), worldUsedNs, unlimited);
        sendFooter(sender);
        if(currentPage==1) sendLegend(sender);
    }

    private void addGroups(List<EntityGroup> out, ListManager lm){ for(Iterator<EntityGroup> it = lm.getGroupIterator(); it.hasNext();) out.add(it.next()); }

    private void sendHeader(ICommandSender sender, TimeManager wm, double usedMs, double allocMs, double pctAlloc, boolean unlimited,
                             double entMs,double tileMs,int entGroups,int tileGroups,int entObjs,int tileObjs){
        EnumChatFormatting pctColor = mod.getColorBand("world:"+world.provider.dimensionId+":alloc", pctAlloc);
        String allocStr = unlimited?"UNL":msFmt.format(allocMs);
        String pctBar = buildBar(pctAlloc, 24, pctColor);
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA+"World "+EnumChatFormatting.GOLD+world.provider.getDimensionName()+EnumChatFormatting.GRAY+" (dim "+world.provider.dimensionId+") "+pctColor+msFmt.format(usedMs)+"ms"+EnumChatFormatting.GRAY+" / "+allocStr+"ms "+pctBar+EnumChatFormatting.RESET+String.format(" %.1f%%", pctAlloc)));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.DARK_GRAY+StringUtils.repeat('-', 80)));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY+"Entities: "+EnumChatFormatting.GREEN+msFmt.format(entMs)+"ms "+pctFmt.format(entMs>0? entMs*100.0/Math.max(usedMs,0.0001):0)+"%  G:"+entGroups+" O:"+entObjs+EnumChatFormatting.GRAY+"  |  Tiles: "+EnumChatFormatting.YELLOW+msFmt.format(tileMs)+"ms "+pctFmt.format(tileMs>0? tileMs*100.0/Math.max(usedMs,0.0001):0)+"%  G:"+tileGroups+" O:"+tileObjs));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.DARK_GRAY+StringUtils.repeat('-', 80)));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD+pad("Group",14)+pad("Used",9)+pad("Alloc",9)+pad("%World",8)+pad("Run/Obj",10)+pad("Slices",8)+pad("Mode",6)+"Load"));
    }

    private void writeGroup(ICommandSender sender, EntityGroup g, long worldUsedNs, boolean worldUnlimited){
        TimedEntities tg = g.getTimedGroup();
        if(tg==null){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+pad(g.getName(),14)+" <no data>")); return; }
        long usedNs = tg.getTimeUsedAverage(); long maxNs = tg.getTimeMax();
        double usedMs = usedNs/(double)TimeManager.timeMilisecond; double allocMs = maxNs/(double)TimeManager.timeMilisecond;
        boolean unlimited = worldUnlimited || maxNs > (mod.defaultTickTime * TimeManager.timeMilisecond * 10L);
        double pctWorld = worldUsedNs>0? usedNs*100.0/worldUsedNs:0;
        EnumChatFormatting pctColor = mod.getColorBand("world:"+world.provider.dimensionId+":group:"+g.getName(), pctWorld);
        String allocStr = unlimited?"UNL":msFmt.format(allocMs);
        String runObj = tg.getObjectsRunAverage()+"/"+g.entities.size();
        String bar = buildBar(pctWorld, 14, pctColor);
        sender.addChatMessage(new ChatComponentText(
            EnumChatFormatting.GRAY+pad(g.getName(),14)+
            EnumChatFormatting.RESET+pad(msFmt.format(usedMs),9)+
            EnumChatFormatting.RESET+pad(allocStr,9)+
            pctColor+pad(String.format("%.1f", pctWorld),8)+
            EnumChatFormatting.RESET+pad(runObj,10)+
            EnumChatFormatting.RESET+pad(String.valueOf(tg.getSliceMax()),8)+
            (unlimited?EnumChatFormatting.YELLOW:EnumChatFormatting.GREEN)+pad(unlimited?"UNL":"DYN",6)+
            bar+EnumChatFormatting.RESET
        ));
    }

    private void sendFooter(ICommandSender sender){
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.DARK_GRAY+StringUtils.repeat('-', 80)));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN+"Page "+currentPage+"/"+maxPages+EnumChatFormatting.GRAY+"  (tickBudget="+mod.defaultTickTime+"ms)"));
    }

    private void sendLegend(ICommandSender sender){
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY+"Legend: "+EnumChatFormatting.GREEN+"DYN"+EnumChatFormatting.GRAY+"=dynamic  "+EnumChatFormatting.YELLOW+"UNL"+EnumChatFormatting.GRAY+"=unlimited  Bars show % world time; world bar shows % alloc or tick budget when UNL."));
    }

    private String buildBar(double pct, int width, EnumChatFormatting color){
        double capped = Math.min(100.0, Math.max(0.0, pct));
        int filled = (int)Math.round((capped/100.0)*width);
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.DARK_GRAY).append('[').append(color);
        for(int i=0;i<width;i++) sb.append(i<filled?'#':'-');
        sb.append(EnumChatFormatting.DARK_GRAY).append(']');
        return sb.toString();
    }

    private String pad(String s,int len){ if(s.length()>=len) return s; return s+StringUtils.repeat(' ', len-s.length()); }

    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return sender.canCommandSenderUseCommand(1, getCommandName()); }
    @Override public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if(args.length == 2) {
            List<String> out = new ArrayList<String>();
            if(mod.server!=null && mod.server.worldServers!=null){ for(WorldServer ws: mod.server.worldServers){ if(ws==null) continue; String id = String.valueOf(ws.provider.dimensionId); String idAlt = "dim"+id; if(id.startsWith(args[1])) out.add(id); if(idAlt.startsWith(args[1])) out.add(idAlt); } }
            return out; }
        if(args.length==3){ List<String> out = new ArrayList<String>(); out.add("1"); return out; }
        return null;
    }
    @Override public boolean isUsernameIndex(String[] a,int i){ return false; }
    @Override public int compareTo(Object o){ return 0; }
}
