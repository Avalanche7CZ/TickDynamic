package com.wildex999.tickdynamic.commands;

import java.util.ArrayList;
import java.util.List;

import com.wildex999.tickdynamic.TickDynamicMod;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

public class CommandIdentify implements ICommand {
    private final TickDynamicMod mod;
    private final List<String> aliases = new ArrayList<String>();

    public CommandIdentify(TickDynamicMod mod){
        this.mod = mod;
        aliases.add("identify");
    }

    @Override public String getCommandName(){ return "tickdynamic identify"; }
    @Override public String getCommandUsage(ICommandSender sender){ return "tickdynamic identify [on|off|toggle|status]"; }
    @Override public List getCommandAliases(){ return null; }

    @Override public void processCommand(ICommandSender sender, String[] args){
        String playerName = sender.getCommandSenderName();
        String action = args.length >= 2 ? args[1].toLowerCase() : "toggle";
        boolean enabled;
        if(action.equals("on")){
            enabled = true; mod.setIdentify(playerName, true);
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN+"Identify mode enabled. Right-click an entity or block to see its TickDynamic group."));
            return;
        } else if(action.equals("off")) {
            mod.clearIdentify(playerName);
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW+"Identify mode disabled."));
            return;
        } else if(action.equals("status")) {
            enabled = mod.isIdentifying(playerName);
            sender.addChatMessage(new ChatComponentText("Identify mode is "+(enabled?EnumChatFormatting.GREEN+"ON":EnumChatFormatting.RED+"OFF"))); return;
        } else if(action.equals("toggle")) {
            enabled = mod.toggleIdentify(playerName);
            sender.addChatMessage(new ChatComponentText((enabled?EnumChatFormatting.GREEN+"Enabled":""+EnumChatFormatting.YELLOW+"Disabled") + EnumChatFormatting.RESET + " identify mode."));
            if(enabled)
                sender.addChatMessage(new ChatComponentText("Right-click entities or tile entities to inspect their group."));
            return;
        } else {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED+"Unknown argument: "+action));
            sender.addChatMessage(new ChatComponentText("Usage: "+getCommandUsage(sender)));
        }
    }

    @Override public boolean canCommandSenderUseCommand(ICommandSender sender){ return sender.canCommandSenderUseCommand(1, "tickdynamic"); }
    @Override public List addTabCompletionOptions(ICommandSender sender, String[] args){
        List<String> out = new ArrayList<String>();
        if(args.length == 2){
            String last = args[1].toLowerCase();
            for(String opt : new String[]{"on","off","toggle","status"}) if(opt.startsWith(last)) out.add(opt);
            return out;
        }
        return null; }
    @Override public boolean isUsernameIndex(String[] a,int i){ return false; }
    @Override public int compareTo(Object o){ return 0; }
}
