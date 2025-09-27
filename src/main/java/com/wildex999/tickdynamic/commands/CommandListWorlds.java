package com.wildex999.tickdynamic.commands;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.wildex999.tickdynamic.TickDynamicMod;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedGroup;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;

public class CommandListWorlds implements ICommand {
    private final TickDynamicMod mod;
    private final DecimalFormat msFmt = new DecimalFormat("#.00");
    private int rowsPerPage = 7;
    private int currentPage;
    private int maxPages;
    private static final int BAR_WIDTH = 16;

    public CommandListWorlds(TickDynamicMod mod) { this.mod = mod; }

    @Override public String getCommandName() { return "tickdynamic listworlds"; }
    @Override public String getCommandUsage(ICommandSender sender) { return "tickdynamic listworlds [page]"; }
    @Override public List getCommandAliases() { return null; }

    @Override public void processCommand(ICommandSender sender, String[] args) {
        if(args.length > 1 && (args[1].equalsIgnoreCase("help")||args[1].equals("?"))) { sender.addChatMessage(new ChatComponentText("Usage: "+getCommandUsage(sender))); return; }
        currentPage = 1; if(args.length >= 2){ try{ currentPage = Integer.parseInt(args[1]); if(currentPage<1) currentPage=1;}catch(NumberFormatException ex){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"Invalid page: "+args[1])); return; }}
        List<WorldServer> worlds = new ArrayList<WorldServer>();
        if(mod.server!=null && mod.server.worldServers!=null) for(WorldServer w: mod.server.worldServers) if(w!=null) worlds.add(w);
        Collections.sort(worlds, new Comparator<WorldServer>(){ public int compare(WorldServer a, WorldServer b){ return Integer.compare(a.provider.dimensionId,b.provider.dimensionId);} });
        int extraLines = 2; int totalLines = worlds.size()+extraLines; maxPages = Math.max(1,(int)Math.ceil(totalLines/(double)rowsPerPage)); if(currentPage>maxPages) currentPage=maxPages;
        double totalUsedMs=0; for(WorldServer w: worlds){ TimeManager tm=mod.getWorldTimeManager(w); if(tm!=null) totalUsedMs += tm.getTimeUsedAverage()/(double)TimeManager.timeMilisecond; }
        double tickBudgetMs = mod.defaultTickTime;
        sendHeaderInteractive(sender,totalUsedMs,tickBudgetMs);
        int startIndex=(currentPage-1)*rowsPerPage; int sent=0; int idx=0;
        for(WorldServer w: worlds){ if(idx++<startIndex) continue; if(sent>=rowsPerPage) break; sendWorldLineInteractive(sender,w,tickBudgetMs); sent++; }
        if(currentPage==maxPages){ if(sent<rowsPerPage){ TimedGroup other=mod.getTimedGroup("other"); if(other!=null){ sendSpecialLine(sender,"Other",'O',other.getTimeUsedAverage()/(double)TimeManager.timeMilisecond,tickBudgetMs); sent++; }} if(sent<rowsPerPage){ TimedGroup external=mod.getTimedGroup("external"); if(external!=null){ sendSpecialLine(sender,"External",'E',external.getTimeUsedAverage()/(double)TimeManager.timeMilisecond,tickBudgetMs); sent++; } } }
        sendFooterInteractive(sender);
        if(currentPage==1) sendLegend(sender);
    }

    private void sendHeaderInteractive(ICommandSender sender,double totalUsed,double budget){
        double pct = budget>0? totalUsed*100.0/budget:0; EnumChatFormatting col = (pct<70?EnumChatFormatting.GREEN:(pct<90?EnumChatFormatting.YELLOW:EnumChatFormatting.RED));
        IChatComponent line = new ChatComponentText(EnumChatFormatting.AQUA+"TickDynamic "+EnumChatFormatting.GRAY+"Total "+col+msFmt.format(totalUsed)+"ms"+EnumChatFormatting.GRAY+" / "+msFmt.format(budget)+"ms ")
                .appendSibling(barComponent(pct,BAR_WIDTH,pctColor(pct),"Total Usage: "+msFmt.format(totalUsed)+"ms\nBudget: "+msFmt.format(budget)+"ms\n"+String.format("%.2f%%",pct)));
        sender.addChatMessage(line);
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.DARK_GRAY+repeat('-', 78)));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD+pad("Dim",5)+pad("World",18)+pad("Used",8)+pad("Alloc",8)+pad("%Alloc",8)+pad("%Tick",7)+pad("Slices",7)+pad("Mode",6)+"Load"));
    }

    private void sendWorldLineInteractive(ICommandSender sender, WorldServer w,double tickBudget){
        TimeManager tm=mod.getWorldTimeManager(w); if(tm==null) return; long usedNs=tm.getTimeUsedAverage(); long maxNs=tm.getTimeMax(); double usedMs=usedNs/(double)TimeManager.timeMilisecond; double allocMs=maxNs/(double)TimeManager.timeMilisecond; boolean unlimited=!mod.dynamicActive || maxNs>(mod.defaultTickTime*TimeManager.timeMilisecond*10L);
        double pctAlloc = unlimited? (tickBudget>0? usedMs*100.0/tickBudget:0):(allocMs>0? usedMs*100.0/allocMs:0); if(pctAlloc>9999)pctAlloc=9999; double pctTick = tickBudget>0? (usedMs*100.0/tickBudget):0; EnumChatFormatting pCol=pctColor(pctAlloc); EnumChatFormatting tCol=pctColor(pctTick);
        String dimStr = formatDim(w.provider.dimensionId);
        String allocStr = unlimited?"UNL":msFmt.format(allocMs);
        String mode = unlimited?"UNL":"DYN";
        IChatComponent dimComp = new ChatComponentText(EnumChatFormatting.GRAY+pad(dimStr,5));
        dimComp.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,new ChatComponentText("Click: open world detail")));
        dimComp.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/tickdynamic world "+w.provider.dimensionId));
        IChatComponent nameComp = new ChatComponentText(EnumChatFormatting.RESET+pad(trim(w.provider.getDimensionName(),18),18));
        nameComp.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,new ChatComponentText("Dimension: "+w.provider.dimensionId)));
        IChatComponent usedComp = new ChatComponentText(EnumChatFormatting.RESET+pad(msFmt.format(usedMs),8));
        IChatComponent allocComp = new ChatComponentText(EnumChatFormatting.RESET+pad(allocStr,8));
        IChatComponent pctAllocComp = new ChatComponentText(pCol+pad(String.format("%.1f",pctAlloc),8));
        IChatComponent pctTickComp = new ChatComponentText(tCol+pad(String.format("%.1f",pctTick),7));
        IChatComponent slicesComp = new ChatComponentText(EnumChatFormatting.RESET+pad(String.valueOf(tm.getSliceMax()),7));
        IChatComponent modeComp = new ChatComponentText((unlimited?EnumChatFormatting.YELLOW:EnumChatFormatting.GREEN)+pad(mode,6));
        String hover = "World: "+w.provider.getDimensionName()+" ("+w.provider.dimensionId+")\nUsed: "+msFmt.format(usedMs)+"ms\nAlloc: "+allocStr+"ms\n%Alloc: "+String.format("%.2f",pctAlloc)+"%\n%Tick: "+String.format("%.2f",pctTick)+"%\nSlices: "+tm.getSliceMax()+"\nMode: "+mode;
        IChatComponent bar = barComponent(pctAlloc,BAR_WIDTH,pCol,hover);
        ChatComponentText line = new ChatComponentText("");
        line.appendSibling(dimComp).appendSibling(nameComp).appendSibling(usedComp).appendSibling(allocComp).appendSibling(pctAllocComp).appendSibling(pctTickComp).appendSibling(slicesComp).appendSibling(modeComp).appendSibling(bar);
        sender.addChatMessage(line);
    }

    private void sendSpecialLine(ICommandSender sender,String label,char tag,double usedMs,double tickBudget){
        double pctTick = tickBudget>0? usedMs*100.0/tickBudget:0; EnumChatFormatting col=pctColor(pctTick);
        String hover=label+" bucket\nUsed: "+msFmt.format(usedMs)+"ms\n%Tick: "+String.format("%.2f",pctTick)+"%";
        ChatComponentText line = new ChatComponentText(EnumChatFormatting.DARK_AQUA+pad(tag== 'O'?"-O-":"-E-",5)+pad(trim('('+label+')',18),18)+pad(msFmt.format(usedMs),8)+pad("UNL",8)+col+pad(String.format("%.1f",pctTick),8)+col+pad(String.format("%.1f",pctTick),7)+pad("--",7)+EnumChatFormatting.YELLOW+pad(String.valueOf(tag),6));
        line.appendSibling(barComponent(pctTick,BAR_WIDTH,col,hover)); sender.addChatMessage(line); }

    private void sendFooterInteractive(ICommandSender sender){
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.DARK_GRAY+repeat('-',78)));
        ChatComponentText nav = new ChatComponentText("");
        if(currentPage>1){ ChatComponentText prev = new ChatComponentText(EnumChatFormatting.AQUA+"[Prev] "); prev.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/tickdynamic listworlds "+(currentPage-1))); prev.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,new ChatComponentText("Page "+(currentPage-1)))); nav.appendSibling(prev);} else nav.appendSibling(new ChatComponentText(EnumChatFormatting.DARK_GRAY+"[Prev] "));
        nav.appendSibling(new ChatComponentText(EnumChatFormatting.GREEN+" Page "+currentPage+"/"+maxPages+" "));
        if(currentPage<maxPages){ ChatComponentText next = new ChatComponentText(EnumChatFormatting.AQUA+"[Next]"); next.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/tickdynamic listworlds "+(currentPage+1))); next.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,new ChatComponentText("Page "+(currentPage+1)))); nav.appendSibling(next);} else nav.appendSibling(new ChatComponentText(EnumChatFormatting.DARK_GRAY+"[Next]"));
        sender.addChatMessage(nav);
    }

    private void sendLegend(ICommandSender sender){
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY+"Legend: "+EnumChatFormatting.GREEN+"DYN"+EnumChatFormatting.GRAY+"=limited  "+EnumChatFormatting.YELLOW+"UNL"+EnumChatFormatting.GRAY+"=unlimited  Bars: usage vs alloc (or tick)  Click dim -> /td world"));
    }

    private IChatComponent barComponent(double pct,int width, EnumChatFormatting baseColor,String hover){ double c = Math.max(0,Math.min(100,pct)); int filled=(int)Math.round((c/100.0)*width); StringBuilder sb=new StringBuilder("["); for(int i=0;i<width;i++){ sb.append(i<filled?"=":"-"); } sb.append("]"); ChatComponentText comp = new ChatComponentText(baseColor+sb.toString()); comp.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,new ChatComponentText(hover))); return comp; }
    private EnumChatFormatting pctColor(double pct){ if(pct<70) return EnumChatFormatting.GREEN; if(pct<90) return EnumChatFormatting.YELLOW; return EnumChatFormatting.RED; }
    private String trim(String s,int len){ if(s==null) return "?"; if(s.length()<=len) return s; return s.substring(0,len-1)+'~'; }
    private String pad(String s,int len){ if(s.length()>=len) return s; return s+repeat(' ',len-s.length()); }
    private String formatDim(int id){ return (id>=0?"+":"")+id; }
    private String repeat(char c,int n){ StringBuilder b=new StringBuilder(n); for(int i=0;i<n;i++) b.append(c); return b.toString(); }

    // ICommand requirements
    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return sender.canCommandSenderUseCommand(1, getCommandName()); }
    @Override public List addTabCompletionOptions(ICommandSender sender, String[] args){ return null; }
    @Override public boolean isUsernameIndex(String[] args, int index){ return false; }
    @Override public int compareTo(Object o){ return 0; }
}
