package com.wildex999.tickdynamic.commands;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import com.wildex999.tickdynamic.TickDynamicMod;
import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.listinject.ListManager;
import com.wildex999.tickdynamic.timemanager.TimeManager;
import com.wildex999.tickdynamic.timemanager.TimedEntities;
import com.wildex999.tickdynamic.timemanager.ITimed;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.DimensionManager;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

public class CommandWorldDiag implements ICommand {
    private final TickDynamicMod mod;
    public CommandWorldDiag(TickDynamicMod mod){ this.mod = mod; }

    @Override public String getCommandName(){ return "tickdynamic diagworld"; }
    @Override public String getCommandUsage(ICommandSender s){ return "tickdynamic diagworld <dim|dimX>"; }
    @Override public List getCommandAliases(){ return null; }

    @Override public void processCommand(ICommandSender sender, String[] args){
        if(args.length < 2){ sender.addChatMessage(new ChatComponentText("Usage: "+getCommandUsage(sender))); return; }
        String dimStr = args[1]; if(dimStr.startsWith("dim")) dimStr = dimStr.substring(3);
        int dim; try { dim = Integer.parseInt(dimStr); } catch(NumberFormatException ex){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"Bad dimension: "+args[1])); return; }
        World world = DimensionManager.getWorld(dim);
        if(world == null){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"World not loaded: "+dim)); return; }
        TimeManager wm = mod.getWorldTimeManager(world);
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA+"[Diag] World dim"+dim+" sliceMax="+wm.getSliceMax()+" timeMax="+wm.getTimeMax()));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY+" loadedEntityList="+world.loadedEntityList.getClass().getName()));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY+" loadedTileEntityList="+world.loadedTileEntityList.getClass().getName()));

        if(world.loadedEntityList instanceof ListManager){
            ListManager lm = (ListManager)world.loadedEntityList;
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD+" Entity groups:"));
            dumpGroups(sender,lm);
        } else sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+" Entity list not injected"));

        if(world.loadedTileEntityList instanceof ListManager){
            ListManager lm = (ListManager)world.loadedTileEntityList;
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD+" TileEntity groups:"));
            dumpGroups(sender,lm);
        } else sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+" TileEntity list not injected"));

        // Dump timed objects referencing this world
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.BLUE+" Timed objects (world subtree):"));
        for(ITimed t : new ArrayList<ITimed>(mod.timedObjects.values())){
            if(t instanceof TimeManager){
                TimeManager tm = (TimeManager)t;
                if(tm.world == world){
                    sender.addChatMessage(new ChatComponentText("  TM: "+tm.name+" slices="+tm.getSliceMax()+" timeMax="+tm.getTimeMax()));
                }
            } else if(t instanceof TimedEntities){
                TimedEntities te = (TimedEntities)t;
                if(te.world == world){
                    sender.addChatMessage(new ChatComponentText("  TE: "+te.getName()+" slices="+te.getSliceMax()+" timeMax="+te.getTimeMax()));
                }
            }
        }
    }

    private void dumpGroups(ICommandSender sender, ListManager lm){
        try {
            java.lang.reflect.Field localGroupsF = ListManager.class.getDeclaredField("localGroups");
            localGroupsF.setAccessible(true);
            java.util.Set<EntityGroup> groups = (java.util.Set<EntityGroup>)localGroupsF.get(lm);
            if(groups.isEmpty()) sender.addChatMessage(new ChatComponentText("  (none)"));
            for(EntityGroup g : groups){
                sender.addChatMessage(new ChatComponentText("  "+g.getName()+" type="+g.getGroupType()+" entities="+g.entities.size()+" slices="+ (g.getTimedGroup()!=null? g.getTimedGroup().getSliceMax():"?") ));
            }
        } catch(Exception e){ sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"  Failed reflection: "+e.getMessage())); }
    }

    @Override public boolean canCommandSenderUseCommand(ICommandSender s){ return s.canCommandSenderUseCommand(1,getCommandName()); }
    @Override public List addTabCompletionOptions(ICommandSender s,String[] a){ return null; }
    @Override public boolean isUsernameIndex(String[] a,int i){ return false; }
    @Override public int compareTo(Object o){ return 0; }
}

