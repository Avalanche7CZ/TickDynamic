package com.wildex999.tickdynamic.commands;

import java.util.List;
import java.util.ArrayList;

import com.wildex999.tickdynamic.TickDynamicMod;
import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedEntities;
import com.wildex999.tickdynamic.timemanager.ITimed;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.DimensionManager;
import net.minecraft.world.World;

public class CommandSetSlices implements ICommand {
    private final TickDynamicMod mod;
    public CommandSetSlices(TickDynamicMod mod){ this.mod = mod; }

    @Override public String getCommandName(){ return "tickdynamic setslices"; }
    @Override public String getCommandUsage(ICommandSender s){ return "tickdynamic setslices <dim|groupPath> <value>"; }
    @Override public List getCommandAliases(){ return null; }

    @Override public void processCommand(ICommandSender sender, String[] args){
        if(args.length < 3){ sender.addChatMessage(new ChatComponentText("Usage: "+getCommandUsage(sender))); return; }
        String target = args[1];
        int value;
        try { value = Integer.parseInt(args[2]); } catch(NumberFormatException ex){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"Invalid value: "+args[2])); return; }
        if(value < 0){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"Value must be >= 0")); return; }

        boolean changed = false;
        // World target (dim or dimX)
        String dimStr = target;
        if(dimStr.startsWith("dim")) dimStr = dimStr.substring(3);
        try {
            int dim = Integer.parseInt(dimStr);
            World w = DimensionManager.getWorld(dim);
            if(w != null){
                TimeManager tm = mod.getWorldTimeManager(w);
                tm.setSliceMax(value);
                tm.writeConfig(true);
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN+"World dim"+dim+" slicesMax set to "+value));
                changed = true;
            }
        } catch(NumberFormatException ignored){ }

        if(changed) return;

        // Try group path: worlds.dim0.tileentity etc.
        ITimed t = mod.getTimeManager(target);
        if(t == null) t = mod.getTimedGroup(target);
        if(t == null){
            EntityGroup g = mod.getEntityGroup(target);
            if(g != null && g.getTimedGroup()!=null) t = g.getTimedGroup();
        }
        if(t == null){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"No world/group found for: "+target)); return; }
        t.setSliceMax(value);
        if(t instanceof TimeManager) ((TimeManager)t).writeConfig(true); else if(t instanceof TimedEntities) ((TimedEntities)t).writeConfig(true);
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN+"Group "+t.getName()+" slicesMax set to "+value));
    }

    @Override public boolean canCommandSenderUseCommand(ICommandSender s){ return s.canCommandSenderUseCommand(2,getCommandName()); }
    @Override public List addTabCompletionOptions(ICommandSender s,String[] a){
        if(a.length == 2){
            List<String> out = new ArrayList<String>();
            // Suggest worlds
            if(mod.server!=null && mod.server.worldServers!=null){ for(net.minecraft.world.WorldServer ws: mod.server.worldServers){ if(ws==null) continue; out.add("dim"+ws.provider.dimensionId); out.add(String.valueOf(ws.provider.dimensionId)); } }
            // Suggest known groups
            for(String key : mod.timedObjects.keySet()) out.add(key);
            for(String key : mod.entityGroups.keySet()) out.add(key);
            return out;
        }
        return null;
    }
    @Override public boolean isUsernameIndex(String[] a,int i){ return false; }
    @Override public int compareTo(Object o){ return 0; }
}

