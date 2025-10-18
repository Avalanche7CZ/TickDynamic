# TickDynamic
### Temporarily maintained by @mayonnaizze on Discord, as their interest and time waxes and wanes.
Public Issues repository for the Minecraft mod Tick Dynamic: Funny Edition.

Fixes up TickDynamic for modern JVMs (LWJGL3-ify) somewhat. 1.7.10 only.

## How it works (0.3.0)

TickDynamic dynamically shares the per-tick time budget across worlds and groups. There are three activation styles:

- Always-active: limiting runs every tick (default if no thresholds are set).
- Threshold: limiting activates only while TPS is below `activationTpsThreshold`.
- Hysteresis: two thresholds to avoid flapping — activates below `dynamicHysteresisActivateBelow`, deactivates above `dynamicHysteresisDeactivateAbove`.

### Isolated per-dimension balancing (optional)

When `isolated.enabled = true`, each world gets a share of the budget based on a weight:
- Base weight = 1.0.
- If `isolated.playerWeight = true`, multiply by `1 + players * isolated.playerWeightScale` (clamped by `isolated.playerWeightMax`).
- If `isolated.skipNoPlayers = true` and the world has no players, its weight is 0 (skipped).
- If `isolated.lessIfNoTiles = true` and the world has zero TileEntities, multiply weight by `isolated.noTilesFactor` to de-prioritize empty worlds.
- Protected worlds (in `isolated.protectDims` or marked special) are floored to at least base weight.

World sliceMax is then set proportional to weight; the root still targets your tick budget.

### TileEntity offender deprioritization (optional)

When `offender.enabled = true`, the hottest TileEntities are gently slowed:
- Intensity-aware penalties (based on recent time use); penalties ramp up/down smoothly.
- Cadence guard (`offender.skipEvery`) ensures periodic runs — nothing starves.
- Near-player bias reduces skipping near players (`nearPlayerRadius`, `nearPlayerBias`).
- Per-chunk and global caps prevent over-focusing one area.
- Exempt critical classes via `excludeClassRegex`.

### Web dashboard (optional)

If `web.enabled = true`, the HTTP dashboard publishes a JSON snapshot on `http://<bind>:<port>`:
- World health: `lastMs`, `budgetMs`, `errorMs`, `avgTPS`.
- Global vs per-world speed: `serverSpeedPercent`, `worldSpeedPercent`.
- Groups: counts, timing, `active` (whether anything ran recently), `tdLimited`, `tdSlowdown`.
- TileEntity visibility: `tileSummary` (counts), `lagTopTiles`/`lagTopChunksTime` (intensity), and `penalizedTop` (penalty, severity, near, prob).

Note: If a group shows `tps = 0.0` and `time = 0.0ms` but `active = false`, it simply means no updates occurred in the current averaging window (common on quiet servers or right after start).

## Configuration

All keys live in the Forge config (and can still be overridden with `-D` system properties). Highlights:

- general/hysteresis:
  - `activationTpsThreshold` or the pair `dynamicHysteresisActivateBelow` + `dynamicHysteresisDeactivateAbove`.
  - `colorHysteresisMarginPercent` for stable percent coloring.
- isolated:
  - `enabled`, `skipNoPlayers`, `playerWeight`, `playerWeightScale`, `playerWeightMax`,
    `protectSpecial`, `lessIfNoTiles`, `noTilesFactor`.
- offender:
  - `enabled`, `top`, `minMs`, `maxPerChunk`, `skipEvery`,
    `penaltyUp`, `penaltyDown`, `skipMinPenalty`,
    `controllerGain`, `controllerMin`, `controllerMax`, `globalCapPercent`,
    `nearPlayerRadius`, `nearPlayerBias`, `excludeClassRegex`.
- web:
  - `enabled`, `bind`, `port`, `token`.

Defaults are conservative; on a fresh config, limiting is ON and smooth, but you may not notice slowdowns unless there’s actual load.

## Changelog

See `CHANGELOG.md` for detailed changes in 0.3.0.
