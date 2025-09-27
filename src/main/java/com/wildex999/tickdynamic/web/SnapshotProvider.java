package com.wildex999.tickdynamic.web;

import com.wildex999.tickdynamic.TickDynamicMod;
import com.wildex999.tickdynamic.listinject.EntityGroup;
import com.wildex999.tickdynamic.listinject.EntityType;
import com.wildex999.tickdynamic.timemanager.TimedEntities;
import com.wildex999.tickdynamic.timemanager.TimedGroup;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.tileentity.TileEntity;

public final class SnapshotProvider {
    private SnapshotProvider() {}

    public static String buildJson(TickDynamicMod mod) {
        StringBuilder sb = new StringBuilder(16_384);
        sb.append('{');
        // header
        field(sb, "version", TickDynamicMod.VERSION).append(',');
        field(sb, "dynamicActive", mod.dynamicActive).append(',');
        num(sb, "lastTickMs", mod.lastTickDurationMs).append(',');
        num(sb, "averageTPS", mod.averageTPS).append(',');
        num(sb, "defaultTickMs", mod.defaultTickTime).append(',');
        field(sb, "safeEntityIteration", TickDynamicMod.safeEntityIteration).append(',');
        field(sb, "disableEntityTimeSlicing", TickDynamicMod.disableEntityTimeSlicing).append(',');
        field(sb, "disableTileEntityControl", TickDynamicMod.disableTileEntityControl).append(',');
        num(sb, "entityMinSliceTickMs", TickDynamicMod.entityMinSliceTickMs).append(',');
        // min time threshold for hotspot display
        num(sb, "minHotspotMs", mod.webMinHotspotMs).append(',');
        // mode info
        boolean hysteresis = mod.activationTpsActivateBelow > 0 && mod.activationTpsDeactivateAbove > mod.activationTpsActivateBelow;
        sb.append("\"mode\":{");
        if(hysteresis) {
            num(sb, "activateBelow", mod.activationTpsActivateBelow).append(',');
            num(sb, "deactivateAbove", mod.activationTpsDeactivateAbove);
        } else {
            num(sb, "threshold", mod.activationTpsThreshold);
        }
        sb.append("},");

        // worlds
        sb.append("\"worlds\":[");
        MinecraftServer server = mod != null ? mod.server : null;
        WorldServer[] arr = server != null ? server.worldServers : null;
        boolean firstWorld = true;
        if(arr != null) {
            for(WorldServer ws : arr) {
                if(ws == null) continue;
                if(!firstWorld) sb.append(','); firstWorld = false;
                worldJson(sb, mod, ws);
            }
        }
        sb.append("]");
        sb.append('}');
        return sb.toString();
    }

    private static void worldJson(StringBuilder sb, TickDynamicMod mod, World w) {
        sb.append('{');
        num(sb, "dimId", w.provider.dimensionId).append(',');
        field(sb, "name", safe(w.provider.getDimensionName())).append(',');
        // operational flags
        field(sb, "tdActive", mod.dynamicActive).append(',');
        num(sb, "tickBudgetMs", mod.defaultTickTime).append(',');
        // expose current player count in world (for UI filtering)
        int players = 0; try { java.util.List<?> pe = (w instanceof net.minecraft.world.WorldServer) ? ((net.minecraft.world.WorldServer)w).playerEntities : null; players = (pe!=null)?pe.size():0; } catch(Throwable ignore) {}
        num(sb, "players", players).append(',');
        // mark special (ignored in server-wide hotspots) per config
        field(sb, "special", mod.isSpecialWorld(w)).append(',');
        // World health snapshot
        sb.append("\"health\":{");
        double lastMs = mod.lastTickDurationMs; double budgetMs = mod.defaultTickTime; double errMs = lastMs - budgetMs;
        num(sb, "lastMs", lastMs).append(',');
        num(sb, "budgetMs", budgetMs).append(',');
        num(sb, "errorMs", errMs).append(',');
        num(sb, "avgTPS", mod.averageTPS);
        sb.append("},");
        // Robust loaded counts for this world (avoid timed-iterator side-effects on size())
        int loadedEnt = 0, loadedTe = 0;
        try {
            com.wildex999.tickdynamic.listinject.ListManager em = (mod!=null && mod.eventHandler!=null) ? mod.eventHandler.getEntityManager(w) : null;
            com.wildex999.tickdynamic.listinject.ListManager tm = (mod!=null && mod.eventHandler!=null) ? mod.eventHandler.getTileManager(w) : null;
            if(em != null) loadedEnt = em.getTotalCount();
            if(tm != null) loadedTe = tm.getTotalCount();
            // Fallback to world lists if managers not injected (e.g., TE control disabled)
            if(loadedEnt <= 0) {
                Object le = w.loadedEntityList;
                if(le instanceof java.util.List) loadedEnt = ((java.util.List<?>)le).size();
            }
            if(loadedTe <= 0) {
                Object lt = w.loadedTileEntityList;
                if(lt instanceof java.util.List) loadedTe = ((java.util.List<?>)lt).size();
            }
        } catch (Throwable t) {
            // ignore; fields may be obfuscated in certain environments
        }
        // groups
        java.util.List<EntityGroup> groups = mod.getWorldEntityGroups(w);
        int totalEnt=0, totalTe=0;
        sb.append("\"groups\":[");
        boolean first = true;
        boolean hasTileEntityGroup = false;
        // For worldSpeedPercent: compute weighted avg of group speeds (entities+tiles)
        double speedSum = 0.0; int speedWeight = 0;
        for(EntityGroup g : groups) {
            if(g == null) continue;
            if(!first) sb.append(','); first = false;
            int count;
            if(g.getGroupType() == EntityType.Entity) {
                count = g.getEntityCount(); totalEnt += count;
            } else {
                hasTileEntityGroup = true;
                if(TickDynamicMod.disableTileEntityControl) {
                    count = loadedTe;
                } else {
                    count = g.getEntityCount();
                    if(count == 0 && loadedTe > 0) {
                        String gn = g.getName();
                        if(gn != null && gn.equalsIgnoreCase("tileentity")) {
                            count = loadedTe;
                        }
                    }
                }
                totalTe += count;
            }
            // Accumulate group speed if known
            try {
                com.wildex999.tickdynamic.timemanager.TimedGroup tg = g.timedGroup;
                if(tg instanceof com.wildex999.tickdynamic.timemanager.TimedEntities) {
                    com.wildex999.tickdynamic.timemanager.TimedEntities te = (com.wildex999.tickdynamic.timemanager.TimedEntities)tg;
                    double sp = (te.averageTPS/20.0)*100.0; if(Double.isNaN(sp)) sp = 100.0; if(sp < 0) sp = 0; if(sp > 100) sp = 100;
                    if(count > 0 && te.getObjectsRunAverage() > 0) { speedSum += sp * count; speedWeight += count; }
                }
            } catch(Throwable ignore) {}
            groupJson(sb, mod, g, count);
        }
        // If no tile entity group present, add a dummy empty group for UI consistency
        if(!hasTileEntityGroup) {
            if(!first) sb.append(',');
            sb.append("{\"name\":\"tileentity\",\"type\":\"tileentity\",\"count\":0,\"slicesMax\":0,\"timing\":{},\"active\":false} ");
        }
        sb.append(']');
        sb.append(',');
        // expose both group-based totals and world loaded counts
        num(sb, "totalEntities", totalEnt).append(',');
        num(sb, "totalTileEntities", totalTe).append(',');
        num(sb, "loadedEntities", loadedEnt).append(',');
        num(sb, "loadedTileEntities", loadedTe).append(',');
        // Server-wide speed percent reflects server TPS (global)
        double serverSpeedPercent = Math.max(0.0, Math.min(100.0, (mod.averageTPS/20.0)*100.0));
        num(sb, "serverSpeedPercent", serverSpeedPercent).append(',');
        // World-specific speed percent (weighted by objects that actually ran)
        double worldSpeedPercent = (speedWeight > 0) ? (speedSum / speedWeight) : 100.0;
        num(sb, "worldSpeedPercent", worldSpeedPercent).append(',');
        // Back-compat: keep speedPercent as server-wide value
        num(sb, "speedPercent", serverSpeedPercent).append(',');

        // Penalization observability
        try {
            int dim = w.provider.dimensionId;
            java.util.concurrent.ConcurrentHashMap<Long, Double> penMap = mod.tileOffenderPenalty.get(Integer.valueOf(dim));
            java.util.concurrent.ConcurrentHashMap<Long, Double> sevMap = mod.tileOffenderSeverity.get(Integer.valueOf(dim));
            int pcount = penMap != null ? penMap.size() : 0;
            num(sb, "penalizedCount", pcount).append(',');
            sb.append("\"penalizedTop\":");
            // Scale guard: avoid scanning 100k+ tiles every snapshot
            int scanMax = Integer.getInteger("tickdynamic.web.penalized.scanMaxTe", 10000);
            boolean skipped = loadedTe > scanMax;
            if(skipped) {
                sb.append("null");
            } else if(pcount > 0) {
                java.util.ArrayList<Object[]> list = new java.util.ArrayList<Object[]>();
                java.util.List<?> raw = (java.util.List<?>)w.loadedTileEntityList;
                if(raw != null) {
                    for(Object o : raw) {
                        if(!(o instanceof net.minecraft.tileentity.TileEntity)) continue;
                        net.minecraft.tileentity.TileEntity te = (net.minecraft.tileentity.Tile.Entity)o;
                        long key = com.wildex999.tickdynamic.TickDynamicMod.packTileKey(te.xCoord, te.yCoord, te.zCoord);
                        double p = penMap != null && penMap.containsKey(key) ? penMap.get(key).doubleValue() : 0.0;
                        if(p <= 0) continue;
                        double s = sevMap != null && sevMap.containsKey(key) ? sevMap.get(key).doubleValue() : 0.0;
                        list.add(new Object[]{ te, Double.valueOf(p), Double.valueOf(s) });
                    }
                }
                java.util.Collections.sort(list, new java.util.Comparator<Object[]>() {
                    @Override public int compare(Object[] a, Object[] b){
                        double sa = ((Double)a[1]).doubleValue() * ((Double)a[2]).doubleValue();
                        double sb = ((Double)b[1]).doubleValue() * ((Double)b[2]).doubleValue();
                        return Double.compare(sb, sa);
                    }
                });
                int lim = Math.min(10, list.size());
                sb.append('[');
                boolean f = true;
                for(int i=0;i<lim;i++){
                    Object[] row = list.get(i);
                    net.minecraft.tileentity.TileEntity te = (net.minecraft.tileentity.Tile.Entity)row[0];
                    double p = ((Double)row[1]).doubleValue();
                    double s = ((Double)row[2]).doubleValue();
                    // Near-player check for prob adjustment
                    boolean near = false; double prob = p; try {
                        if(mod.tileOffenderNearPlayerBias < 1.0){
                            int r = Math.max(1, mod.tileOffenderNearPlayerRadius); int r2 = r*r;
                            java.util.List<?> pls = (w instanceof net.minecraft.world.WorldServer) ? ((net.minecraft.world.WorldServer)w).playerEntities : w.playerEntities;
                            if(pls != null && !pls.isEmpty()){
                                for(Object po : pls){ if(!(po instanceof net.minecraft.entity.player.EntityPlayer)) continue; net.minecraft.entity.player.EntityPlayer pl=(net.minecraft.entity.player.EntityPlayer)po; int dx=((int)pl.posX)-te.xCoord; int dy=((int)pl.posY)-te.yCoord; int dz=((int)pl.posZ)-te.zCoord; int d2=dx*dx+dy*dy+dz*dz; if(d2<=r2){ near=true; break; } }
                            }
                            if(near) prob = p * Math.max(0.0, mod.tileOffenderNearPlayerBias);
                        }
                        if(prob > 0.95) prob = 0.95; if(prob < 0) prob = 0;
                    } catch(Throwable ignore) {}
                    if(!f) sb.append(','); f=false;
                    sb.append('{');
                    num(sb, "x", te.xCoord).append(',');
                    num(sb, "y", te.yCoord).append(',');
                    num(sb, "z", te.zCoord).append(',');
                    num(sb, "penalty", p).append(',');
                    num(sb, "severity", s).append(',');
                    field(sb, "near", near).append(',');
                    num(sb, "prob", prob);
                    sb.append('}');
                }
                sb.append(']');
            } else {
                sb.append("null");
            }
            sb.append(',');
        } catch(Throwable t) { sb.append("\"penalizedCount\":0,\"penalizedTop\":null,\"penalizedSkipped\":false,\"penalizedScanMax\":0,"); }

        // Time-based hotspots first: lagging tiles with coordinates and lagging chunks by time
        sb.append("\"lagTopTiles\":");
        try {
            java.util.List<com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.TileStat> tiles = com.wildex999.tickdynamic.listinject.CustomProfiler.lagIndex.snapshotTopTiles(w.provider.dimensionId, 5);
            sb.append('[');
            boolean f=true;
            for(com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.TileStat ts: tiles){
                if(!f) sb.append(','); f=false;
                String pretty = friendlyTileName(w, ts.x, ts.y, ts.z, ts.name);
                sb.append('{');
                field(sb, "name", safe(pretty)).append(',');
                num(sb, "x", ts.x).append(',');
                num(sb, "y", ts.y).append(',');
                num(sb, "z", ts.z).append(',');
                num(sb, "totalMs", ts.totalNs/1_000_000.0).append(',');
                num(sb, "hits", ts.hits);
                sb.append('}');
            }
            sb.append(']');
        } catch(Throwable t) { sb.append("null"); }
        sb.append(',');
        sb.append("\"lagTopChunksTime\":");
        try {
            java.util.List<com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.ChunkStat> chunksT = com.wildex999.tickdynamic.listinject.CustomProfiler.lagIndex.snapshotTopChunks(w.provider.dimensionId, 5);
            sb.append('['); boolean f2=true; for(com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.ChunkStat cs: chunksT){ if(!f2) sb.append(','); f2=false; sb.append('{'); num(sb, "cx", cs.cx).append(','); num(sb, "cz", cs.cz).append(','); num(sb, "totalMs", cs.totalNs/1_000_000.0).append(','); num(sb, "hits", cs.hits); sb.append('}'); } sb.append(']');
        } catch(Throwable t) { sb.append("null"); }
        sb.append(',');
        // Aggregate lagging tile types by time (per world)
        sb.append("\"lagTopTileTypes\":");
        try {
            java.util.List<com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.TileStat> tiles = com.wildex999.tickdynamic.listinject.CustomProfiler.lagIndex.snapshotTopTiles(w.provider.dimensionId, 100);
            java.util.HashMap<String,double[]> agg = new java.util.HashMap<String,double[]>(); // name -> [totalMs, hits]
            for(com.wildex999.tickdynamic.listinject.CustomProfiler.LagIndex.TileStat ts: tiles){
                String fname = friendlyTileName(w, ts.x, ts.y, ts.z, ts.name);
                double[] arr = agg.get(fname);
                if(arr == null){ arr = new double[]{0.0, 0.0}; agg.put(fname, arr); }
                arr[0] += ts.totalNs/1_000_000.0;
                arr[1] += ts.hits;
            }
            java.util.List<java.util.Map.Entry<String,double[]>> sorted = new java.util.ArrayList<java.util.Map.Entry<String,double[]>>(agg.entrySet());
            java.util.Collections.sort(sorted, new java.util.Comparator<java.util.Map.Entry<String,double[]>>() { @Override public int compare(java.util.Map.Entry<String,double[]> a, java.util.Map.Entry<String,double[]> b){ return Double.compare(b.getValue()[0], a.getValue()[0]); } });
            sb.append('[');
            int nType=0; boolean fType=true; for(java.util.Map.Entry<String,double[]> e : sorted){ if(nType++>=10) break; if(!fType) sb.append(','); fType=false; double[] v=e.getValue(); sb.append('{'); field(sb, "name", safe(e.getKey())).append(','); num(sb, "totalMs", v[0]).append(','); num(sb, "hits", (long)v[1]); sb.append('}'); }
            sb.append(']');
        } catch(Throwable t) { sb.append("null"); }
        sb.append(',');
        // Count-based summaries
        sb.append("\"tileSummary\":");
        com.wildex999.tickdynamic.listinject.ListManager tileMgr2 = mod.eventHandler != null ? mod.eventHandler.getTileManager(w) : null;
        java.util.List<Object[]> list = null;
        if(tileMgr2 != null) {
            try {
                list = tileMgr2.snapshotTopTileCounts(10);
            } catch(Throwable t) {
                // Log the error for debugging
                if(TickDynamicMod.debug) {
                    System.out.println("[TickDynamic] Failed to get tile counts from ListManager for DIM " + w.provider.dimensionId + ": " + t.getMessage());
                }
            }
        }
        if(list == null) list = new java.util.ArrayList<Object>();

        // Fallback: if list is empty but we know there are loaded TEs, aggregate by scanning
        boolean hadGroupTiles = false;
        if(list.isEmpty() && loadedTe > 0) {
            // Performance limit: don't scan worlds with more than 10,000 TEs using the slow method
            int maxScanLimit = Integer.getInteger("tickdynamic.web.tileScanLimit", 10000);
            boolean skipSlowScan = loadedTe > maxScanLimit;

            if(!skipSlowScan) {
                java.util.HashMap<String, Integer> cnt = new java.util.HashMap<String, Integer>(); // key = registry:meta
                try {
                    for(EntityGroup g : groups) {
                        if(g == null || g.getGroupType() != EntityType.TileEntity) continue;
                        java.util.ArrayList<com.wildex999.tickdynamic.listinject.EntityObject> el = g.entities;
                        if(el != null && !el.isEmpty()) hadGroupTiles = true;
                        for(com.wildex999.tickdynamic.listinject.EntityObject eo : el) {
                            net.minecraft.tileentity.TileEntity te = eo.TD_selfTileEntity;
                            if(te == null) continue;
                            net.minecraft.world.World ww = te.getWorldObj(); if(ww == null) continue;
                            net.minecraft.block.Block b = ww.getBlock(te.xCoord, te.yCoord, te.zCoord);
                            int meta = ww.getBlockMetadata(te.xCoord, te.yCoord, te.zCoord);
                            String reg;
                            try { reg = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getNameForObject(b); } catch(Throwable t) { reg = String.valueOf(b); }
                            String key = reg + ":" + meta;
                            Integer prev = cnt.get(key);
                            cnt.put(key, prev==null?1:prev+1);
                        }
                    }
                    java.util.List<java.util.Map.Entry<String,Integer>> sorted = new java.util.ArrayList<java.util.Map.Entry<String,Integer>>(cnt.entrySet());
                    java.util.Collections.sort(sorted, new java.util.Comparator<java.util.Map.Entry<String,Integer>>() { @Override public int compare(java.util.Map.Entry<String,Integer>a, java.util.Map.Entry<String,Integer>b){ return Integer.compare(b.getValue(), a.getValue()); } });
                    int lim = Math.min(5, sorted.size());
                    for(int i=0;i<lim;i++) {
                        java.util.Map.Entry<String,Integer> e = sorted.get(i);
                        String key = e.getKey(); int p = key.lastIndexOf(':');
                        String reg = (p>0)?key.substring(0,p):key; int meta = 0; try{ meta = Integer.parseInt((p>0)?key.substring(p+1):"0"); }catch(Throwable ignored){}
                        list.add(new Object[]{ reg, Integer.valueOf(meta), Integer.valueOf(e.getValue().intValue()) });
                    }
                } catch(Throwable t) {
                    if(TickDynamicMod.debug) {
                        System.out.println("[TickDynamic] Failed to scan entity groups for tile summary in DIM " + w.provider.dimensionId + ": " + t.getMessage());
                    }
                }
            }
        }

        // Raw fallback: if still empty (no tile groups or manager), scan world's loadedTileEntityList
        if(list.isEmpty() && loadedTe > 0 && !hadGroupTiles) {
            // Enhanced performance limit for direct world scanning - increase for large modpacks like GTNH
            int maxDirectScanLimit = Integer.getInteger("tickdynamic.web.directScanLimit", 30000);

            if(loadedTe <= maxDirectScanLimit) {
                try {
                    java.util.List<?> raw = (java.util.List<?>)w.loadedTileEntityList;
                    if(raw != null && !raw.isEmpty()) {
                        java.util.HashMap<String, Integer> cnt2 = new java.util.HashMap<String, Integer>();
                        int scanned = 0;
                        int maxScanCount = Math.min(raw.size(), maxDirectScanLimit);

                        for(Object o : raw) {
                            if(scanned >= maxScanCount) break; // Performance guard
                            if(!(o instanceof net.minecraft.tileentity.TileEntity)) continue;
                            net.minecraft.tileentity.TileEntity te = (net.minecraft.tileentity.Tile.Entity)o;
                            try {
                                net.minecraft.block.Block b = w.getBlock(te.xCoord, te.yCoord, te.zCoord);
                                int meta = w.getBlockMetadata(te.xCoord, te.yCoord, te.zCoord);
                                String reg; try { reg = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getNameForObject(b); } catch(Throwable t) { reg = String.valueOf(b); }
                                String key = reg + ":" + meta;
                                Integer prev = cnt2.get(key); cnt2.put(key, prev==null?1:prev+1);
                                scanned++;
                            } catch(Throwable teError) {
                                // Skip individual problematic tile entities
                                continue;
                            }
                        }
                        java.util.List<java.util.Map.Entry<String,Integer>> sorted2 = new java.util.ArrayList<java.util.Map.Entry<String,Integer>>(cnt2.entrySet());
                        java.util.Collections.sort(sorted2, new java.util.Comparator<java.util.Map.Entry<String,Integer>>() { @Override public int compare(java.util.Map.Entry<String,Integer>a, java.util.Map.Entry<String,Integer>b){ return Integer.compare(b.getValue(), a.getValue()); } });
                        int lim2 = Math.min(10, sorted2.size());
                        for(int i=0;i<lim2;i++) {
                            java.util.Map.Entry<String,Integer> e = sorted2.get(i);
                            String key = e.getKey(); int p = key.lastIndexOf(':');
                            String reg = (p>0)?key.substring(0,p):key; int meta = 0; try{ meta = Integer.parseInt((p>0)?key.substring(p+1):"0"); }catch(Throwable ignored){}
                            list.add(new Object[]{ reg, Integer.valueOf(meta), Integer.valueOf(e.getValue().intValue()) });
                        }

                        if(TickDynamicMod.debug && scanned < raw.size()) {
                            System.out.println("[TickDynamic] Tile summary scan limited: scanned " + scanned + " of " + raw.size() + " TEs in DIM " + w.provider.dimensionId);
                        }
                    }
                } catch(Throwable t) {
                    if(TickDynamicMod.debug) {
                        System.out.println("[TickDynamic] Failed to scan raw tile entity list for DIM " + w.provider.dimensionId + ": " + t.getMessage());
                    }
                }
            } else {
                if(TickDynamicMod.debug) {
                    System.out.println("[TickDynamic] Skipping tile summary for DIM " + w.provider.dimensionId + " - too many TEs (" + loadedTe + " > " + maxDirectScanLimit + ")");
                }
            }
        }
        if(!list.isEmpty()) {
            sb.append('[');
            boolean firstT = true;
            for(Object[] row : list) {
                if(!firstT) sb.append(','); firstT=false;
                String reg = String.valueOf(row[0]); int meta = ((Integer)row[1]).intValue(); int count = ((Integer)row[2]).intValue();
                // Derive a friendly display name via ItemStack, and extract modId from registry name
                String modId = ""; String modName = ""; String display = "";
                try {
                    int cpos = reg.indexOf(':'); if(cpos > 0) modId = reg.substring(0, cpos);
                    net.minecraft.block.Block b = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getRaw(reg);
                    if(b != null) {
                        net.minecraft.item.Item it = net.minecraft.item.Item.getItemFromBlock(b);
                        if(it != null) {
                            net.minecraft.item.ItemStack st = new net.minecraft.item.ItemStack(it, 1, meta);
                            try { display = st.getDisplayName(); } catch(Throwable ignore) {}
                        }
                    }
                    try {
                        cpw.mods.fml.common.ModContainer mc = cpw.mods.fml.common.Loader.isModLoaded(modId) ? cpw.mods.fml.common.Loader.instance().getIndexedModList().get(modId) : null;
                        if(mc != null) modName = mc.getName();
                    } catch(Throwable ignore) {}
                } catch(Throwable ignore) {}
                if(display == null || display.isEmpty()) display = reg + ":" + meta;
                sb.append('{');
                field(sb, "name", safe(reg)).append(',');
                field(sb, "display", safe(display)).append(',');
                field(sb, "modId", safe(modId)).append(',');
                field(sb, "modName", safe(modName)).append(',');
                num(sb, "meta", meta).append(',');
                num(sb, "count", count);
                sb.append('}');
            }
            sb.append(']');
        } else {
            sb.append("null");
        }
        sb.append(',');
        sb.append("\"topChunks\":");
        try {
            java.util.HashMap<Long, Integer> chunks = new java.util.HashMap<Long, Integer>();
            for(EntityGroup g : groups) {
                if(g == null || g.getGroupType() != EntityType.TileEntity) continue;
                java.util.ArrayList<com.wildex999.tickdynamic.listinject.EntityObject> entitiesList = g.entities;
                for(com.wildex999.tickdynamic.listinject.EntityObject eo : entitiesList) {
                    net.minecraft.tileentity.TileEntity te = eo.TD_selfTileEntity;
                    if(te == null) continue;
                    int cx = te.xCoord >> 4; int cz = te.zCoord >> 4;
                    long key = (((long)cx) << 32) ^ (cz & 0xffffffffL);
                    Integer prev = chunks.get(key);
                    chunks.put(key, prev==null?1:prev+1);
                }
            }
            // Raw fallback if no groups
            if(chunks.isEmpty() && loadedTe > 0) {
                java.util.List<?> raw = (java.util.List<?>)w.loadedTileEntityList;
                if(raw != null) {
                    for(Object o : raw) {
                        if(!(o instanceof net.minecraft.tileentity.TileEntity)) continue;
                        net.minecraft.tileentity.TileEntity te = (net.minecraft.tileentity.TileEntity)o;
                        int cx = te.xCoord >> 4; int cz = te.zCoord >> 4;
                        long key = (((long)cx) << 32) ^ (cz & 0xffffffffL);
                        Integer prev = chunks.get(key); chunks.put(key, prev==null?1:prev+1);
                    }
                }
            }
            java.util.List<java.util.Map.Entry<Long,Integer>> sorted = new java.util.ArrayList<java.util.Map.Entry<Long,Integer>>(chunks.entrySet());
            java.util.Collections.sort(sorted, new java.util.Comparator<java.util.Map.Entry<Long,Integer>>() { @Override public int compare(java.util.Map.Entry<Long,Integer>a, java.util.Map.Entry<Long,Integer>b){ return Integer.compare(b.getValue(), a.getValue()); } });
            sb.append('[');
            int n=0; boolean firstC=true; for(java.util.Map.Entry<Long,Integer> e : sorted){ if(n++>=5) break; if(!firstC) sb.append(','); firstC=false; int cx=(int)(e.getKey()>>32); int cz=(int)(long)e.getKey(); sb.append('{'); num(sb, "cx", cx).append(','); num(sb, "cz", cz).append(','); num(sb, "count", e.getValue().intValue()); sb.append('}'); }
            sb.append(']');
        } catch(Throwable t) { sb.append("null"); }
        sb.append('}');
    }

    private static void groupJson(StringBuilder sb, TickDynamicMod mod, EntityGroup g) {
        groupJson(sb, mod, g, g.getEntityCount());
    }

    private static void groupJson(StringBuilder sb, TickDynamicMod mod, EntityGroup g, int forcedCount) {
        sb.append('{');
        field(sb, "name", safe(g.getName())).append(',');
        field(sb, "path", safe(nullToEmpty(g.getConfigEntry()))).append(',');
        field(sb, "type", g.getGroupType() == EntityType.Entity ? "entity" : "tileentity").append(',');
        // If TE control is disabled, present this group as disabled in the UI (no timing/slicing applied)
        boolean teControlDisabled = (TickDynamicMod.disableTileEntityControl && g.getGroupType() == EntityType.TileEntity);
        boolean enabled = g.enabled && g.valid && !teControlDisabled;
        field(sb, "enabled", enabled).append(',');
        int cnt = forcedCount;
        num(sb, "count", cnt).append(',');
        TimedGroup tg = g.timedGroup;
        // Also expose slices at the group root for UI
        num(sb, "slicesMax", tg!=null?tg.getSliceMax():0).append(',');
        // Pre-compute group speed (how fast this group is effectively running)
        double gSpeedPct = 100.0;
        long objectsRunAvg = 0;
        if(tg instanceof TimedEntities) {
            TimedEntities te = (TimedEntities)tg;
            double sp = (te.averageTPS/20.0)*100.0;
            if(Double.isNaN(sp)) sp = 100.0;
            if(sp < 0) sp = 0; else if(sp > 100) sp = 100;
            gSpeedPct = sp;
            objectsRunAvg = te.getObjectsRunAverage();
        } else if(tg != null) {
            objectsRunAvg = tg.getObjectsRunAverage();
        }
        // If TE control is disabled, force speed to 100% (we're not limiting them)
        if(teControlDisabled) {
            gSpeedPct = 100.0;
        }
        // We'll fill timing object now using UI-friendly keys/units (milliseconds)
        sb.append("\"timing\":{");
        num(sb, "sliceMax", tg!=null?tg.getSliceMax():0).append(',');
        long tmaxNs = tg!=null?tg.getTimeMax():0L;
        long avgNs = tg!=null?tg.getTimeUsedAverage():0L;
        long lastNs = tg!=null?tg.getTimeUsedLast():0L;
        // mark unlimited if far above default budget
        long unlimitedThresh = 2L * com.wildex999.tickdynamic.timemanager.ITimed.timeMilisecond * (long)mod.defaultTickTime;
        boolean unlimited = tmaxNs > unlimitedThresh;
        field(sb, "unlimited", unlimited).append(',');
        // expose in milliseconds as doubles for the UI
        num(sb, "maxMs", tmaxNs/1_000_000.0).append(',');
        num(sb, "avgMs", avgNs/1_000_000.0).append(',');
        num(sb, "lastMs", lastNs/1_000_000.0).append(',');
        num(sb, "runAvg", tg!=null?tg.getObjectsRunAverage():0);
        if(tg instanceof TimedEntities) {
            TimedEntities te = (TimedEntities)tg;
            sb.append(','); num(sb, "avgTPS", te.averageTPS);
            sb.append(','); num(sb, "minObjects", te.getMinimumObjects());
            sb.append(','); num(sb, "minTPS", te.getMinimumTPS());
            sb.append(','); num(sb, "minTime", te.getMinimumTime());
        }
        sb.append('}');
        // Compute and expose slowdown/TD-limited metrics at group level
        boolean hasObjects = forcedCount > 0;
        boolean hasRunActivity = objectsRunAvg > 0;
        // Mark activity for UI (helps interpret zeros)
        sb.append(','); field(sb, "active", hasObjects && hasRunActivity);
        // Do not mark limited if TE control is globally disabled
        boolean tdLimited = !teControlDisabled && mod.dynamicActive && !unlimited && g.enabled && g.valid && hasObjects && hasRunActivity && (gSpeedPct < 99.5);
        sb.append(','); num(sb, "speedPercent", (teControlDisabled ? 100.0 : (hasObjects && hasRunActivity) ? gSpeedPct : 100.0));
        sb.append(','); field(sb, "tdLimited", tdLimited);
        double slowdown = tdLimited ? (100.0 - gSpeedPct) : 0.0;
        if(slowdown < 0) slowdown = 0; if(slowdown > 100) slowdown = 100;
        // also expose tdSlowdown for the UI
        sb.append(','); num(sb, "tdSlowdown", (teControlDisabled ? 0.0 : (hasObjects && hasRunActivity) ? slowdown : 0.0));
        sb.append('}');
    }

    private static StringBuilder field(StringBuilder sb, String name, String val) {
        sb.append('"').append(name).append('"').append(':');
        sb.append('"').append(escape(val==null?"":val)).append('"');
        return sb;
    }
    private static StringBuilder field(StringBuilder sb, String name, boolean val) {
        sb.append('"').append(name).append('"').append(':');
        sb.append(val ? "true" : "false");
        return sb;
    }
    private static StringBuilder num(StringBuilder sb, String name, double val) {
        sb.append('"').append(name).append('"').append(':');
        sb.append(Double.isNaN(val)?0.0:val);
        return sb;
    }
    private static StringBuilder num(StringBuilder sb, String name, long val) {
        sb.append('"').append(name).append('"').append(':');
        sb.append(val);
        return sb;
    }
    private static StringBuilder num(StringBuilder sb, String name, int val) {
        sb.append('"').append(name).append('"').append(':');
        sb.append(val);
        return sb;
    }
    private static String safe(String s) { return s==null?"":s; }
    private static String nullToEmpty(String s) { return s==null?"":s; }
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length()+8);
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            switch(c){
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if(c < 32) { out.append("\\u"); String h = Integer.toHexString(c | 0x10000).substring(1); out.append(h); }
                    else out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Best-effort friendly name for a TileEntity at the given position.
     * Attempts GregTech meta tile names via reflection, falls back to inventory/display names,
     * then class simple name, then the provided fallback.
     */
    private static String friendlyTileName(net.minecraft.world.World w, int x, int y, int z, String fallback) {
        try {
            net.minecraft.tileentity.TileEntity te = w.getTileEntity(x, y, z);
            if(te == null) return fallback;
            try {
                Class<?> cls = te.getClass();
                java.lang.reflect.Method getMTE = null;
                try { getMTE = cls.getMethod("getMetaTileEntity"); } catch (NoSuchMethodException ignore) {}
                if(getMTE != null) {
                    Object mte = getMTE.invoke(te);
                    if(mte != null) {
                        String name = tryNameMethods(mte, "getInventoryName", "getLocalName", "getMetaTileName", "getName");
                        if(name != null && !name.isEmpty()) return name;
                    }
                }
                String name = tryNameMethods(te, "getInventoryName", "getLocalizedName", "getName");
                if(name != null && !name.isEmpty()) return name;
                return cls.getSimpleName();
            } catch (Throwable gtIgnore) {}
        } catch(Throwable ignore) {}
        return fallback;
    }

    private static String tryNameMethods(Object o, String... methods) {
        for(String m : methods) {
            try {
                java.lang.reflect.Method mm = o.getClass().getMethod(m);
                Object r = mm.invoke(o);
                if(r instanceof String) {
                    String s = (String) r;
                    if(s != null && !s.isEmpty()) return s;
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }
}
