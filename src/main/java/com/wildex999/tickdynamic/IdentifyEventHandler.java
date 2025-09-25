package com.wildex999.tickdynamic;

import java.lang.reflect.Field;

import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.timemanager.TimedEntities;
import com.wildex999.tickdynamic.timemanager.ITimed;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

public class IdentifyEventHandler {
    private final TickDynamicMod mod;
    public IdentifyEventHandler(TickDynamicMod mod){ this.mod = mod; }

    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event){
        if(event.entityPlayer == null || event.entityPlayer.worldObj == null) return;
        if(event.world.isRemote) return;
        EntityPlayer player = event.entityPlayer;
        if(!mod.isIdentifying(player.getCommandSenderName())) return;

        if(event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK){
            handleTile(player, event.world, event.x, event.y, event.z);
        }
        if(event.action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR || event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK){
            Entity looked = getNearestEntity(player, 6.0D);
            if(looked != null) handleEntity(player, looked);
        }
    }

    private EntityGroup extractGroup(Object obj){
        if(obj == null) return null;
        try {
            Field f = null;
            Class<?> c = obj.getClass();
            while(c != null){
                try { f = c.getDeclaredField("TD_entityGroup"); break; } catch(NoSuchFieldException e){ c = c.getSuperclass(); }
            }
            if(f == null) return null;
            f.setAccessible(true);
            Object g = f.get(obj);
            if(g instanceof EntityGroup) return (EntityGroup)g;
        } catch (Throwable ignored) {}
        return null;
    }

    private void handleTile(EntityPlayer player, World world, int x, int y, int z){
        TileEntity te = world.getTileEntity(x,y,z);
        if(te == null) return;
        EntityGroup group = extractGroup(te);
        if(group == null){ player.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW+"[TickDynamic] Tile not managed by any group.")); return; }
        sendGroupInfo(player, group, "Tile");
    }

    private void handleEntity(EntityPlayer player, Entity entity){
        EntityGroup group = extractGroup(entity);
        if(group == null) return;
        sendGroupInfo(player, group, entity.getClass().getSimpleName());
    }

    private void sendGroupInfo(EntityPlayer player, EntityGroup group, String label){
        TimedEntities timed = group.getTimedGroup();
        long usedAvg = timed == null ? 0 : timed.getTimeUsedAverage();
        long max = timed == null ? 0 : timed.getTimeMax();
        double usedMs = usedAvg / (double) ITimed.timeMilisecond;
        double maxMs = max / (double) ITimed.timeMilisecond;
        int count = group.getEntityCount();
        String name = group.getName();
        String dim = group.getWorld()==null?"?":String.valueOf(group.getWorld().provider.dimensionId);
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA+"[TD] "+label+" -> Group "+EnumChatFormatting.GREEN+name+EnumChatFormatting.AQUA+" (dim "+dim+")"));
        player.addChatMessage(new ChatComponentText("  Entities: "+count+" Time(avg/max ms): "+String.format("%.3f/%.3f", usedMs, maxMs)+(timed!=null?" SliceMax="+timed.getSliceMax():"")));
    }

    private Entity getNearestEntity(EntityPlayer player, double range){
        World w = player.worldObj; Entity closest=null; double best=range*range; double px=player.posX, py=player.posY, pz=player.posZ;
        for(Object o : w.loadedEntityList){
            if(!(o instanceof Entity)) continue; Entity e=(Entity)o; if(e==player) continue; double dx=e.posX-px, dy=e.posY-py, dz=e.posZ-pz; double d=dx*dx+dy*dy+dz*dz; if(d<best){ best=d; closest=e; }
        }
        return closest;
    }
}
