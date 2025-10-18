# Changelog

All notable changes to this project will be documented in this file.

## [0.3.0] - 2025-09-27

### Added
- Health-aware, intensity-based TileEntity deprioritization ("offender-first"):
  - Severity-driven penalties with graceful ramp-up/down (no instant toggles).
  - Cadence guard (`skipEvery`) guarantees periodic runs (no starvation).
  - Near-player bias (reduced skipping near players).
  - Per-chunk cap on penalized offenders to avoid tunnel vision.
  - Global cap on penalized population to avoid overreach.
  - Exemption by class regex for critical TEs you never want penalized.
- Isolated per-dimension balancing mode:
  - Player-weighted fairness (more players => more budget).
  - Option to skip worlds with zero players.
  - Protection floors for selected dimensions and special/ignored worlds.
  - Optional reduction of world weight when a world has zero TileEntities.
- Web dashboard observability:
  - World health section (last tick ms, budget, error, avgTPS).
  - Per-world and global speed: `worldSpeedPercent` and `serverSpeedPercent`.
  - Offender visibility: `penalizedCount` and `penalizedTop` (x,y,z, penalty, severity, near, prob).
  - Group-level `active` flag to clarify zeroed metrics when nothing ran in the recent window.
  - Robust fallbacks to keep `tileSummary` (counts) populated.
- Percent color hysteresis to reduce flicker in CLI outputs.
- Config-backed tuning for all of the above (see README "Configuration").

### Changed
- Default dynamic limiting behavior clarified into three modes: Always-active, Threshold, and Hysteresis (with proper activation/deactivation boundaries).
- Isolated mode now actually redistributes world budgets based on weights and protections (previously a stub).

### Fixed
- Safer timed iteration for entities/Tes (graceful abort on concurrent modifications, optional safe mode auto-enable after repeated fallbacks).
- SnapshotProvider robustness and type fixes (correct generics, safe fallbacks, near-player computations).
- Web server hot-reloads on bind/port/token changes from config.

### Configuration (new/updated keys)
- isolated:
  - `enabled`, `skipNoPlayers`, `playerWeight`, `playerWeightScale`, `playerWeightMax`,
    `protectSpecial`, `lessIfNoTiles`, `noTilesFactor`.
- offender:
  - `enabled`, `top`, `minMs`, `maxPerChunk`, `skipEvery`,
    `penaltyUp`, `penaltyDown`, `skipMinPenalty`,
    `controllerGain`, `controllerMin`, `controllerMax`, `globalCapPercent`,
    `nearPlayerRadius`, `nearPlayerBias`, `excludeClassRegex`.
- general/hysteresis:
  - `activationTpsThreshold` or `dynamicHysteresisActivateBelow` + `dynamicHysteresisDeactivateAbove`.
- web:
  - `enabled`, `bind`, `port`, `token`.

### Notes
- Many UI values (avgTPS, time) read 0.0 if a group didn’t run in the sampling window; refer to the `active` flag to distinguish “no activity yet” from “limited.”
- TileEntity control can be disabled globally; if disabled, TE scheduling won’t be affected but UI still shows counts and hotspots where possible.


