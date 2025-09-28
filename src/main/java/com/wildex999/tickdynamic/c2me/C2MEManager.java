package com.wildex999.tickdynamic.c2me;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.wildex999.tickdynamic.TickDynamicMod;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.chunk.Chunk;

public class C2MEManager {
    private final TickDynamicMod mod;
    private boolean useWhitelist;
    private int[] dimWhitelist;
    private final Map<String,Integer> recentAttemptsTick;
    private int internalTick;
    private int lastPrefetchCount;
    private long totalPrefetched;

    public C2MEManager(TickDynamicMod mod){
        this.mod = mod;
        recentAttemptsTick = new HashMap<String,Integer>(4096);
        buildWhitelist();
    }

    public void reloadWhitelist(){ buildWhitelist(); }

    private void buildWhitelist(){
        String[] wl = mod.c2meDimensionWhitelist;
        if(wl != null && wl.length > 0){
            int[] parsed = new int[wl.length];
            int p=0; for(String s: wl){ try{ parsed[p++] = Integer.parseInt(s.trim()); }catch(NumberFormatException ignore){} }
            dimWhitelist = parsed; useWhitelist = true;
        } else { dimWhitelist = null; useWhitelist = false; }
    }

    public void shutdown(){ recentAttemptsTick.clear(); }
    private boolean dimAllowed(int dim){ if(!useWhitelist) return true; for(int d: dimWhitelist) if(d==dim) return true; return false; }

    public void tick(){
        internalTick++; lastPrefetchCount = 0;
        if(!mod.c2meEnabled || mod.server == null || mod.c2mePrefetchRadius <= 0) return;
        if(mod.averageTPS < mod.c2mePrefetchMinTps) return;
        if((internalTick & 127) == 0) pruneOldAttempts(400);
        List players = mod.server.getConfigurationManager().playerEntityList; if(players.isEmpty()) return;
        int budget = mod.c2mePrefetchPerTick;
        for(Object o: players){ if(budget<=0) break; if(!(o instanceof EntityPlayerMP)) continue; EntityPlayerMP p=(EntityPlayerMP)o; WorldServer ws=(WorldServer)p.worldObj; if(ws==null) continue; if(!dimAllowed(ws.provider.dimensionId)) continue; budget = prefetchForPlayer(ws,p,budget); }
    }

    // One-shot burst for login/respawn/dimension change; returns number of chunks attempted
    public int prefetchBurstForPlayer(WorldServer world, EntityPlayerMP player, int radius, int budget){
        if(world == null || player == null) return 0;
        if(budget <= 0 || radius <= 0) return 0;
        if(!dimAllowed(world.provider.dimensionId)) return 0;
        int viewDist = 10; try { viewDist = mod.server.getConfigurationManager().getViewDistance(); } catch(Throwable ignore){}
        int centerChunkX = (int)Math.floor(player.posX) >> 4; int centerChunkZ = (int)Math.floor(player.posZ) >> 4;
        int startR = viewDist + 1; int endR = viewDist + radius; if(endR < startR) return 0;
        ChunkProviderServer cps = (ChunkProviderServer)world.theChunkProviderServer;
        int attempted = 0;
        for(int r=startR; r<=endR && budget>0; r++){
            for(int dx=-r; dx<=r && budget>0; dx++){
                int absDx = dx<0?-dx:dx; int remaining = r-absDx; int[] dzCandidates = new int[]{-remaining, remaining};
                for(int dz: dzCandidates){ if(dz==0 && remaining!=0) continue; int x=centerChunkX+dx; int z=centerChunkZ+dz; if(chunkLoaded(cps,x,z)) continue; String key=world.provider.dimensionId+":"+x+":"+z; Integer last=recentAttemptsTick.get(key); if(last!=null && internalTick-last<60) continue; try{ Chunk c=cps.provideChunk(x,z); if(c!=null){ totalPrefetched++; lastPrefetchCount++; budget--; attempted++; recentAttemptsTick.put(key, internalTick); if(mod.c2meDebug) System.out.println("[TickDynamic][C2ME] Burst prefetched dim="+world.provider.dimensionId+" x="+x+" z="+z); } }catch(Throwable t){ if(mod.c2meDebug) System.out.println("[TickDynamic][C2ME] Burst prefetch failed: "+t.getMessage()); recentAttemptsTick.put(key, internalTick);} if(budget<=0) break; }
            }
        }
        return attempted;
    }

    private void pruneOldAttempts(int age){ Iterator<Map.Entry<String,Integer>> it = recentAttemptsTick.entrySet().iterator(); while(it.hasNext()){ Map.Entry<String,Integer> e = it.next(); if(internalTick - e.getValue() > age) it.remove(); } }

    private int prefetchForPlayer(WorldServer world, EntityPlayerMP player, int budget){
        int viewDist = 10; try { viewDist = mod.server.getConfigurationManager().getViewDistance(); } catch(Throwable ignore){}
        int centerChunkX = (int)Math.floor(player.posX) >> 4; int centerChunkZ = (int)Math.floor(player.posZ) >> 4;
        int startR = viewDist + 1; int endR = viewDist + mod.c2mePrefetchRadius; if(endR < startR) return budget;
        ChunkProviderServer cps = (ChunkProviderServer)world.theChunkProviderServer;
        for(int r=startR; r<=endR && budget>0; r++){
            for(int dx=-r; dx<=r && budget>0; dx++){
                int absDx = dx<0?-dx:dx; int remaining = r-absDx; int[] dzCandidates = new int[]{-remaining, remaining};
                for(int dz: dzCandidates){ if(dz==0 && remaining!=0) continue; int x=centerChunkX+dx; int z=centerChunkZ+dz; if(chunkLoaded(cps,x,z)) continue; String key=world.provider.dimensionId+":"+x+":"+z; Integer last=recentAttemptsTick.get(key); if(last!=null && internalTick-last<60) continue; try{ Chunk c=cps.provideChunk(x,z); if(c!=null){ totalPrefetched++; lastPrefetchCount++; budget--; recentAttemptsTick.put(key,internalTick); if(mod.c2meDebug) System.out.println("[TickDynamic][C2ME] Prefetched dim="+world.provider.dimensionId+" x="+x+" z="+z); } }catch(Throwable t){ if(mod.c2meDebug) System.out.println("[TickDynamic][C2ME] Prefetch failed: "+t.getMessage()); recentAttemptsTick.put(key,internalTick);} if(budget<=0) break; }
            }
        }
        return budget;
    }

    private boolean chunkLoaded(ChunkProviderServer cps, int x, int z){ return cps.chunkExists(x, z); }
    public int getLastPrefetchCount(){ return lastPrefetchCount; }
    public long getTotalPrefetched(){ return totalPrefetched; }
    public int getInternalTick(){ return internalTick; }
    public int getRecentAttemptSize(){ return recentAttemptsTick.size(); }
}
