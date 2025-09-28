package com.wildex999.tickdynamic.c2me;

import com.wildex999.tickdynamic.TickDynamicMod;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;

public class C2MEEvents {
    private final TickDynamicMod mod;

    public C2MEEvents(TickDynamicMod mod){ this.mod = mod; }

    @SubscribeEvent
    public void onLogin(PlayerLoggedInEvent e){
        tryBurst(e.player);
    }

    @SubscribeEvent
    public void onRespawn(PlayerRespawnEvent e){
        tryBurst(e.player);
    }

    @SubscribeEvent
    public void onChangedDim(PlayerChangedDimensionEvent e){
        tryBurst(e.player);
    }

    private void tryBurst(Object playerObj){
        if(!mod.c2meEnabled) return;
        if(!(playerObj instanceof EntityPlayerMP)) return;
        if(mod.averageTPS < mod.c2mePrefetchMinTps) return;
        EntityPlayerMP p = (EntityPlayerMP)playerObj;
        if(!(p.worldObj instanceof WorldServer)) return;
        if(mod.c2meManager == null) return;
        // 1-shot burst: up to 4x normal per-tick budget to warm cache
        int budget = Math.max(1, mod.c2mePrefetchPerTick * 4);
        int radius = Math.max(0, mod.c2mePrefetchRadius);
        try {
            mod.c2meManager.prefetchBurstForPlayer((WorldServer)p.worldObj, p, radius, budget);
        } catch(Throwable t){ if(mod.c2meDebug) System.out.println("[TickDynamic][C2ME] burst error: "+t.getMessage()); }
    }
}

