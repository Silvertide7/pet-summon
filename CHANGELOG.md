## 1.0.1
---
### Added
- Bond allowlist / denylist entity tags (replaces the old `cant_bond` tag) for controlling which mobs can be bonded.
- Config option to allow summoning while swimming (`allowWaterSummon`, default on). Pet spawns at the player's position when no land footprint is available.

### Changed
- Cross-dimension and same-dimension-far summons now route through vanilla's `Entity.changeDimension`, preserving mod-attached state better. Mods that explicitly opt out of cross-dim transfer are respected — the summon fails cleanly rather than corrupting the entity.
- Roster screen reflects cap changes (e.g. PMMO level-ups) immediately on open instead of lagging by a sync round-trip.

## 1.0.0
---
Initial Release.

### Highlights
- Bond, summon, dismiss, and break system for tameable pets, with a roster screen.
- Summon keybind, hold-to-confirm gates on summon / dismiss / break.
- Active pet selection, per-bond rename, manual reordering.
- PMMO integration: gate bond claims behind a configurable skill, with `ALL_OR_NOTHING` and `LINEAR` cap modes.
- Optional XP-level cost per bond claim.
- Biome and dimension tags for blocking summons in specific areas.
- Per-bond and per-player summon cooldowns; revival cooldown for non-permanent deaths.
- Cross-dimension summon support (configurable).
- Death handling: permanent or revivable, with pre-drop snapshots so revived pets keep their gear.
- `BondClaimEvent` for external mods / datapacks to cancel claims.
- Configurable max bonds, saddleable-only mode, summon space requirement, walk vs. teleport range.
