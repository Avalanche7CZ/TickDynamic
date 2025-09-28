package com.wildex999.tickdynamic.listinject;

import com.wildex999.tickdynamic.TickDynamicMod;
import net.minecraft.profiler.Profiler;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class CustomProfiler extends Profiler {

    public enum Stage {
        None,
        BeforeLoop,
        InLoop,
        InTick,
        InRemove
    }

    public final Profiler original;
    public final net.minecraft.world.World ownerWorld;

    public Stage stage;
    public boolean reachedTile;

    private long currentObjStartNs = 0L;
    private EntityObject currentEO = null;
    private int depthCount = 0;

    public static final LagIndex lagIndex = new LagIndex();
    // Captured TE from vanilla tick iteration without swapping lists
    public static final ThreadLocal<net.minecraft.tileentity.TileEntity> LAST_TE_FETCHED = new ThreadLocal<net.minecraft.tileentity.TileEntity>();
    // Manual per-TE timing when vanilla iterates tiles (no list swap)
    private net.minecraft.tileentity.TileEntity manualCurrentTe;
    private long manualStartNs;
    private boolean manualActive;

    public synchronized void manualSwitchTe(net.minecraft.tileentity.TileEntity te) {
        if (te == null) return;
        if (currentEO != null && currentEO.TD_selfTileEntity != null) return;
        long now = System.nanoTime();
        if (manualActive && manualCurrentTe != null) {
            try {
                net.minecraft.world.World w = manualCurrentTe.getWorldObj();
                net.minecraft.world.World wEff = (w != null ? w : ownerWorld);
                if (wEff != null) {
                    lagIndex.recordTile(
                        wEff.provider.dimensionId,
                        manualCurrentTe.xCoord,
                        manualCurrentTe.yCoord,
                        manualCurrentTe.zCoord,
                        manualCurrentTe,
                        now - manualStartNs
                    );
                }
            } catch(Throwable ignore) {}
        }
        manualCurrentTe = te;
        manualStartNs = now;
        manualActive = true;
    }

    public synchronized void manualFlush() {
        if (currentEO != null && currentEO.TD_selfTileEntity != null) { manualActive = false; manualCurrentTe = null; return; }
        if (!manualActive || manualCurrentTe == null) return;
        long now = System.nanoTime();
        try {
            net.minecraft.world.World w = manualCurrentTe.getWorldObj();
            net.minecraft.world.World wEff = (w != null ? w : ownerWorld);
            if (wEff != null) {
                lagIndex.recordTile(
                    wEff.provider.dimensionId,
                    manualCurrentTe.xCoord,
                    manualCurrentTe.yCoord,
                    manualCurrentTe.zCoord,
                    manualCurrentTe,
                    now - manualStartNs
                );
            }
        } catch(Throwable ignore) {}
        manualActive = false;
        manualCurrentTe = null;
    }

    public CustomProfiler(Profiler originalProfiler, net.minecraft.world.World world) {
        this.original = originalProfiler;
        this.ownerWorld = world;
        this.stage = Stage.None;
        this.reachedTile = false;
    }

    public void setStage(Stage next) {
        if (this.stage != next && TickDynamicMod.debugProfiler) {
            System.out.println("[TickDynamic][Profiler] Stage change: " + this.stage + " -> " + next);
        }
        this.stage = next;
    }

    private static final Set<String> LOOP_START_SECTIONS = parseSystemSet(
            "tickdynamic.profiler.loopStart",
            "regular,entities"
    );
    private static final Set<String> ENTITY_TICK_SECTIONS = parseSystemSet(
            "tickdynamic.profiler.entityTick",
            "tick,entityTick"
    );
    private static final Set<String> REMOVE_SECTIONS = parseSystemSet(
            "tickdynamic.profiler.remove",
            "remove"
    );
    private static final Set<String> TILE_START_SECTIONS = parseSystemSet(
            "tickdynamic.profiler.tileStart",
            "blockEntities,tileEntities"
    );
    private static final Set<String> TILE_TICK_SECTIONS = parseSystemSet(
        "tickdynamic.profiler.tileTick",
        "tick,update,updateTileEntity,tileEntityTick"
    );
    private static final boolean HEURISTICS_ENABLED = Boolean.parseBoolean(
        System.getProperty("tickdynamic.profiler.enableHeuristics", "true")
    );
    private static final long MIN_RECORD_NS = Long.getLong(
        "tickdynamic.profiler.minRecordNs",
        200_000L
    );

    private static Set<String> parseSystemSet(String key, String defaults) {
        String raw = System.getProperty(key, defaults);
        Set<String> set = new HashSet<String>();
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (String part : parts) {
                String s = part.trim();
                if (!s.isEmpty()) set.add(s);
            }
        }
        return set;
    }

    private static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String s, String a, String b) {
        return s.contains(a) && s.contains(b);
    }

    private boolean isLoopStart(String s) {
        return LOOP_START_SECTIONS.contains(s)
            || (HEURISTICS_ENABLED && s.contains("entity") && !s.contains("block") && !s.contains("tile"));
    }

    private boolean isEntityTickStart(String s) {
        return ENTITY_TICK_SECTIONS.contains(s)
            || (HEURISTICS_ENABLED && containsAny(s, "tick", "ent"));
    }

    private boolean isRemove(String s) {
        return REMOVE_SECTIONS.contains(s);
    }

    private boolean isTileListStart(String s) {
        return TILE_START_SECTIONS.contains(s)
            || (HEURISTICS_ENABLED && (s.contains("tile") || s.contains("blockentities") || s.contains("blockentity")));
    }

    private boolean isTileTickStart(String s) {
        return TILE_TICK_SECTIONS.contains(s)
            || (HEURISTICS_ENABLED && s.contains("tick") && (s.contains("tile") || s.contains("block")));
    }

    private void beginTickForCurrentObject() {
        setStage(Stage.InTick);
        depthCount = 0;
        currentObjStartNs = System.nanoTime();
        currentEO = EntityIteratorTimed.CURRENT.get();
    }

    @Override
    public void startSection(String sectionName) {
        if (TickDynamicMod.debugProfiler) {
            System.out.println("[TickDynamic][Profiler] startSection: " + sectionName + " (stage=" + stage + ")");
        }
        String s = lower(sectionName);
        switch (stage) {
            case None: {
                // Do nothing until the loop start is encountered
                break;
            }
            case BeforeLoop: {
                if (isLoopStart(s)) {
                    setStage(Stage.InLoop);
                }
                break;
            }
            case InLoop: {
                if (isEntityTickStart(s)) {
                    beginTickForCurrentObject();
                } else if (isRemove(s)) {
                    setStage(Stage.InRemove);
                    depthCount = 0;
                } else if (isTileListStart(s)) {
                    reachedTile = true;
                } else if (reachedTile && isTileTickStart(s)) {
                    beginTickForCurrentObject();
                } else if (reachedTile) {
                    beginTickForCurrentObject();
                }
                break;
            }
            case InTick: {
                // Special-case: a removal begins while inside a tick
                if (depthCount-- <= 0 && isRemove(s)) {
                    setStage(Stage.InRemove);
                    depthCount = 0;
                    break;
                }
                // Intentional fallthrough behavior: treat nested sections as depth growth
                // (preserve original semantics)
            }
            case InRemove: {
                depthCount++;
                break;
            }
        }
        original.startSection(sectionName);
    }

    @Override
    public void endSection() {
        switch (stage) {
            case InTick: {
                if (depthCount-- <= 0) {
                    long dur = System.nanoTime() - currentObjStartNs;
                    if (dur >= MIN_RECORD_NS) {
                        if (currentEO != null && currentEO.TD_selfTileEntity != null) {
                            try {
                                net.minecraft.tileentity.TileEntity te = currentEO.TD_selfTileEntity;
                                net.minecraft.world.World w = te.getWorldObj();
                                if (w != null) {
                                    lagIndex.recordTile(
                                        w.provider.dimensionId,
                                        te.xCoord,
                                        te.yCoord,
                                        te.zCoord,
                                        te,
                                        dur
                                    );
                                }
                            } catch (Throwable ignored) {
                            }
                        } else if (reachedTile) {
                            if (!manualActive) {
                                net.minecraft.tileentity.TileEntity te = LAST_TE_FETCHED.get();
                                if (te != null) {
                                    try {
                                        net.minecraft.world.World w = te.getWorldObj();
                                        net.minecraft.world.World wEff = (w != null ? w : ownerWorld);
                                        if (wEff != null) {
                                            lagIndex.recordTile(
                                                wEff.provider.dimensionId,
                                                te.xCoord,
                                                te.yCoord,
                                                te.zCoord,
                                                te,
                                                dur
                                            );
                                        }
                                    } catch (Throwable ignore) {
                                    } finally {
                                        LAST_TE_FETCHED.remove();
                                    }
                                } else {
                                    if (ownerWorld != null && dur > 0) {
                                        try {
                                            com.wildex999.tickdynamic.timemanager.TimedEntities grp =
                                                TickDynamicMod.tickDynamic != null
                                                    ? TickDynamicMod.tickDynamic.getWorldTimedGroup(ownerWorld, "tileentity", true, true)
                                                    : null;
                                            if (grp != null) grp.setTimeUsed(grp.getTimeUsed() + dur);
                                        } catch (Throwable ignore) {}
                                    }
                                }
                            }
                        }
                    }
                    currentObjStartNs = 0L;
                    currentEO = null;
                    setStage(Stage.InLoop);
                }
                break;
            }
            case InRemove: {
                if (depthCount-- <= 0) setStage(Stage.InLoop);
                break;
            }
            default:
                break;
        }
        original.endSection();
    }

    public static final class LagIndex {
        private static final int MAX_TILES = Integer.getInteger("tickdynamic.profiler.maxTiles", 2000);
        private static final int MAX_CHUNKS = Integer.getInteger("tickdynamic.profiler.maxChunks", 1000);
        private static final double DECAY_FACTOR = Double.parseDouble(System.getProperty("tickdynamic.profiler.decayFactor", "0.98"));

        private static final class TileKey {
            final int dim, x, y, z;
            TileKey(int d, int x, int y, int z) { this.dim = d; this.x = x; this.y = y; this.z = z; }
            @Override public int hashCode() { int h = dim; h = 31 * h + x; h = 31 * h + y; h = 31 * h + z; return h; }
            @Override public boolean equals(Object o) {
                if (!(o instanceof TileKey)) return false;
                TileKey t = (TileKey) o;
                return t.dim == dim && t.x == x && t.y == y && t.z == z;
            }
        }

        private static final class ChunkKey {
            final int dim, cx, cz;
            ChunkKey(int d, int cx, int cz) { this.dim = d; this.cx = cx; this.cz = cz; }
            @Override public int hashCode() { int h = dim; h = 31 * h + cx; h = 31 * h + cz; return h; }
            @Override public boolean equals(Object o) {
                if (!(o instanceof ChunkKey)) return false;
                ChunkKey k = (ChunkKey) o;
                return k.dim == dim && k.cx == cx && k.cz == cz;
            }
        }

        public static final class TileStat {
            public int dim, x, y, z;
            public String name;
            public long totalNs;
            public int hits;
        }

        public static final class ChunkStat {
            public int dim, cx, cz;
            public long totalNs;
            public int hits;
        }

        private final java.util.HashMap<TileKey, TileStat> tiles = new java.util.HashMap<TileKey, TileStat>();
        private final java.util.HashMap<ChunkKey, ChunkStat> chunks = new java.util.HashMap<ChunkKey, ChunkStat>();

        public synchronized void recordTile(int dim, int x, int y, int z, net.minecraft.tileentity.TileEntity te, long ns) {
            ChunkKey ck = new ChunkKey(dim, x >> 4, z >> 4);
            ChunkStat cs = chunks.get(ck);
            if (cs == null) {
                cs = new ChunkStat();
                cs.dim = dim;
                cs.cx = ck.cx;
                cs.cz = ck.cz;
                chunks.put(ck, cs);
                if (chunks.size() > MAX_CHUNKS) trimChunks();
            }
            cs.totalNs += ns;
            cs.hits++;

            TileKey tk = new TileKey(dim, x, y, z);
            TileStat ts = tiles.get(tk);
            if (ts == null) {
                ts = new TileStat();
                ts.dim = dim;
                ts.x = x;
                ts.y = y;
                ts.z = z;
                try {
                    ts.name = cpw.mods.fml.common.registry.GameData.getBlockRegistry().getNameForObject(
                        te.getWorldObj().getBlock(x, y, z)
                    );
                } catch (Throwable t) {
                    ts.name = String.valueOf(te.getBlockType());
                }
                tiles.put(tk, ts);
                if (tiles.size() > MAX_TILES) trimTiles();
            }
            ts.totalNs += ns;
            ts.hits++;
        }

        private void trimTiles() {
            if (tiles.isEmpty()) return;
            int drop = Math.max(1, tiles.size() / 4);
            java.util.List<TileStat> list = new java.util.ArrayList<TileStat>(tiles.values());
            java.util.Collections.sort(list, new java.util.Comparator<TileStat>() {
                @Override public int compare(TileStat a, TileStat b) {
                    return Long.compare(a.totalNs, b.totalNs);
                }
            });
            for (int i = 0; i < drop; i++) {
                TileStat ts = list.get(i);
                tiles.remove(new TileKey(ts.dim, ts.x, ts.y, ts.z));
            }
        }

        private void trimChunks() {
            if (chunks.isEmpty()) return;
            int drop = Math.max(1, chunks.size() / 4);
            java.util.List<ChunkStat> list = new java.util.ArrayList<ChunkStat>(chunks.values());
            java.util.Collections.sort(list, new java.util.Comparator<ChunkStat>() {
                @Override public int compare(ChunkStat a, ChunkStat b) {
                    return Long.compare(a.totalNs, b.totalNs);
                }
            });
            for (int i = 0; i < drop; i++) {
                ChunkStat cs = list.get(i);
                chunks.remove(new ChunkKey(cs.dim, cs.cx, cs.cz));
            }
        }

        public synchronized java.util.List<TileStat> snapshotTopTiles(int dim, int limit) {
            java.util.ArrayList<TileStat> list = new java.util.ArrayList<TileStat>();
            for (TileStat ts : tiles.values()) if (ts.dim == dim) list.add(ts);
            java.util.Collections.sort(list, new java.util.Comparator<TileStat>() {
                @Override public int compare(TileStat a, TileStat b) {
                    return Long.compare(b.totalNs, a.totalNs);
                }
            });
            if (list.size() > limit) list.subList(limit, list.size()).clear();
            return list;
        }

        public synchronized java.util.List<ChunkStat> snapshotTopChunks(int dim, int limit) {
            java.util.ArrayList<ChunkStat> list = new java.util.ArrayList<ChunkStat>();
            for (ChunkStat cs : chunks.values()) if (cs.dim == dim) list.add(cs);
            java.util.Collections.sort(list, new java.util.Comparator<ChunkStat>() {
                @Override public int compare(ChunkStat a, ChunkStat b) {
                    return Long.compare(b.totalNs, a.totalNs);
                }
            });
            if (list.size() > limit) list.subList(limit, list.size()).clear();
            return list;
        }

        public synchronized void decay() {
            if (DECAY_FACTOR >= 1.0) return; // disable decay when >= 1
            for (TileStat ts : tiles.values()) {
                ts.totalNs = (long) Math.max(0L, Math.floor(ts.totalNs * DECAY_FACTOR));
            }
            for (ChunkStat cs : chunks.values()) {
                cs.totalNs = (long) Math.max(0L, Math.floor(cs.totalNs * DECAY_FACTOR));
            }
        }

        public synchronized long getNsAt(int dim, int x, int y, int z) {
            TileStat ts = tiles.get(new TileKey(dim, x, y, z));
            return ts == null ? 0L : ts.totalNs;
        }

        public synchronized int getHitsAt(int dim, int x, int y, int z) {
            TileStat ts = tiles.get(new TileKey(dim, x, y, z));
            return ts == null ? 0 : ts.hits;
        }
    }
}
