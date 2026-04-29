# Kindred — Possible Features

Catalog of features to consider, grouped by scope. Treat tier 1 as the MVP definition; everything below is opt-in. Not a roadmap — a menu.

**Status legend** — ✅ shipped · ⬜ pending · ❌ skipped (with reason)

---

## Tier 1 — Core (MVP)

The minimum to ship.

- ✅ **Multi-pet roster**, configurable max — default now **10**.
- ✅ **Keybind opens a custom screen.** Default `G` (Open Roster).
- ✅ **Direct summon keybind** — summons the player's **active pet**, bypassing the screen. Default `V`. Active pet is set explicitly via the "Set Active" button in the preview pane.
  - Storage: `Optional<UUID> activePetId` on `BondRoster`. Cleared automatically when the active bond is broken or the roster goes empty.
  - First-bond invariant: `tryClaim` auto-sets the first bond as active so the keybind is never useless after the first bond.
  - `C2SSetActivePet(Optional<UUID>)`, `BondView.isActive`, `S2CRosterSync` carries it.
- ✅ **Dismiss / unsummon.** Per-row screen button. Snapshots the pet's NBT before discarding. Eject passengers/stop-riding. Particles + sound on discard. HP preserved as-is.
- ✅ **Claim flow** — server-validated via `C2SCheckBindCandidate` round-trip on screen open; the Bind button only appears for entities the server confirms are bindable.
- ✅ **Break-bond flow** — per-row X button with 3s "Confirm" arm window. Materializes dismissed pets next to player first so they aren't deleted.
- ✅ **Summon button** per row → walk if close, teleport if far in same dim, materialize from snapshot if cross-dim or unloaded.
- ✅ **Cross-dimensional summon**.
- ✅ **Anti-dupe** via per-bond revision counter on attachments + world `SavedData`; stale-revision joins are cancelled.
- ✅ **Persistence across player death** — `AttachmentType.copyOnDeath(true)`.
- ✅ **Bond eligibility = vanilla `OwnableEntity` + owner-match.**
- ✅ **Datapack blocklist tag** `#kindred:bond_blocklist`.
- ✅ **Server-side configs** (`ModConfigSpec`). See [Config.java](src/main/java/net/silvertide/kindred/config/Config.java) for the live list.
- ✅ **Summon sound + particles** — `PORTAL_TRIGGER` (configurable) + `POOF` particles on materialize.
- ✅ **Lang file** with all user-facing strings under `kindred.*` / `key.kindred.*`.

---

## Tier 2 — Polish & quality of life

Nice things that make it feel finished, but you can ship without them.

### Shipped

- ✅ **Rename pets** from the screen — inline edit (click Rename in preview pane → type → Enter to commit, Esc to cancel). Stored in `Bond.displayName`, syncs to the live entity's `customName` and visibility, also pulled back from a vanilla nametag if the player uses one.
- ✅ **Pet preview in the menu** — fixed pane on the right (option B). `InventoryScreen.renderEntityInInventoryFollowsMouse` with `LivingEntity` instances built lazily by `PreviewEntityCache` from each bond's snapshot NBT. Snapshot is captured fresh per sync from the live entity if loaded, so saddle/armor/equipment changes flow into the preview without waiting for a chunk unload. Cache cleared on every `S2CRosterSync` arrival to invalidate stale preview entities. See `NOTES.md` for the saddle-flag access transformer.
- ✅ **Set primary pet** — "Set Active" button in the preview pane (no star-toggling).
- ✅ **Cancel hold-to-confirm on damage** — config `cancelHoldOnDamage` (default true). Server-side `LivingDamageEvent.Pre` → `S2CCancelHold` ping cancels both keybind and screen-button holds.
- ✅ **Death-revival cooldown** — config `revivalCooldownSeconds` (default 0 = disabled). `Optional<Long> diedAt` on `Bond`. New `SummonResult.REVIVAL_PENDING`. Surfaced in the screen as "Respawning Xs" and blocks the keybind. Clears on successful summon.
- ✅ **Global summon cooldown** — config `summonGlobalCooldownSeconds` (default 10s). Per-player tracker (`GlobalSummonCooldownTracker`). New `SummonResult.GLOBAL_COOLDOWN`. Remaining ms ships in `S2CRosterSync` and renders as "Summon Cooldown: X.Xs" in the title bar.
- ✅ **Server-validated bind candidate** — `C2SCheckBindCandidate` on screen open, server replies `S2CBindCandidateResult(uuid, canBind, denyMessageKey)`. Bind button hidden until confirmed. Deny reason replaces the "look at a tamed pet" hint with specific messaging ("You must tame this pet before bonding it" etc.).
- ✅ **Smarter spawn placement** — 5×5 footprint search ranked by `forwardness − 0.2 × lateral + 0.05 × dist` so the pet prefers a spot in front of the player. On-player tile is last resort. `dy = -1..3` column search handles 1-block step-up + 3-block step-down. New `SummonResult.PLAYER_AIRBORNE` when player is too far off the ground.
- ✅ **Suppress loot/XP drops on bonded-pet death** — config `dropLootOnDeath` (default true preserves vanilla). When false, `LivingDropsEvent` and `LivingExperienceDropEvent` are cancelled for entities with `Bonded` attachment. Pairs with revival cooldown so revived pets keep saddle/armor/chest contents.
- ✅ **Materialize-before-break for dismissed pets** — `Bond.dismissed` flag set in `BondManager.dismiss()`, cleared in `materializeFresh`. `breakBond` materializes next to the player only when `dismissed=true` (i.e. the pet would otherwise be deleted because no chunk-version exists). Pets in unloaded chunks are *not* teleported on break — they stay where the player left them.
- ✅ **Bond count display** — "X/Y" in the title bar's left side, matching the cooldown indicator on the right.
- ✅ **Loaded vs stored state in subtitle** — "Horse · Overworld" when loaded, "Horse · Resting" when dismissed/stored. Driven by `BondView.loaded` (server checks `BondIndex.find(...).isPresent()`).
- ✅ **Disabled buttons clearly distinct** — Summon and Dismiss buttons get a much darker bg and dimmed text when disabled (revival pending, on cooldown, or not loaded for dismiss).
- ✅ **Cooldown indicator** — clock-style radial sweep centered on the Summon button while on cooldown (replaces the label entirely). Wedge fills counter-clockwise as the cooldown drains (MOBA convention). Uses whichever cooldown is dominant (per-bond vs. global); revival pending continues to show the "Respawning Xs" label as before. Title-bar global cooldown text uses coarse `Xh Ym` / `Xm Ys` / `Xs` units.
- ✅ **Reorder rows** — `Move Up` / `Move Down` split-row at the top of the preview pane's action stack. Server-authoritative via `C2SReorderBond(bondId, delta)`; row order is the `BondRoster.bonds` `LinkedHashMap` iteration order, persisted via codec. `sendRosterSync` no longer re-sorts on bondedAt — wire order is the player's chosen order.

### Pending

- ⬜ **Search / filter** input at top of roster.
- ⬜ **Last-known-location** display per row — "Overworld · 200,64,-410 · 5m ago" for `loaded=false` rows.
- ⬜ **Sound packs** — datapack-overridable summon SFX per entity type.
- ⬜ **Auto-mount toggle per bond** — overrides global `autoMount` config; only meaningful for saddleable bonds.

### Skipped

- ❌ **Quick stats per row** (HP / speed / jump) — rejected as UI noise; bond rows are intentionally minimal. Could revisit if leveling/perks features ever ship and stats become meaningful gameplay info.
- ❌ **Confirm dialog when breaking a bond on a chest/armor pet** — superseded by the "materialize before break" flow above. Pets are no longer deleted, so the dialog's safety value evaporated.

---

## Tier 3 — Bigger features

Worth doing if you commit to the mod long-term.

- **Custom ownership for non-`OwnableEntity` saddleables** (pigs, striders) — a `SaddleOwner` entity attachment written when first saddled by an unowned player. Unblocks bonding pigs/striders.
- ✅ **XP-level cost to bond** — config `bondXpLevelCost` (int, default `0` = free). `checkClaimEligibility` rejects with `ClaimResult.NOT_ENOUGH_XP` when `player.experienceLevel < cost` (creative mode bypasses); `tryClaim` charges via `giveExperienceLevels(-cost)` after the bond writes commit. Screen footer previews the cost above the Bind button as `Cost: X levels`, in soft red when the player can't afford. Could combine with PMMO integration later so bond gates can require either skill levels (PMMO) or vanilla XP (this).
- **Stable / barn block (optional)** — if you change your mind on "no items/blocks": a block that displays roster pets as live entities. Pure visual; bonds are still the source of truth. Disabled by default.
- **Equipment persistence** — saddle, armor, chest contents, wolf armor. Already covered if you snapshot full NBT, but explicitly tested as a feature.
- **Revival cost on permadeath** — when `deathIsPermanent=true`, optionally allow revival by consuming a configurable item (gold/diamond/totem/datapack-defined ingredient list).
- **XP / leveling per pet** — accumulate XP from time spent near owner / time ridden / kills assisted, unlock perks.
- **Pet perks / traits** — random or rare traits (Surefooted: no fall damage, Frostpaws: doesn't slip on ice, Beast of Burden: +chest slots, Loyal: faster summon). Datapack-defined.
- **Trust / loyalty stat** — feeding, brushing, time near owner increases loyalty; high-loyalty pets come faster, low-loyalty pets ignore the first call.
- **Multi-player bonds** — co-bond a pet to a party so both players can summon. Permission-gated.
- ✅ **Banned-zone tags** `#kindred:no_summon_dimensions` (`TagKey<DimensionType>`) and `#kindred:no_summon_biomes` (`TagKey<Biome>`) — datapack control over summoning. Checked against the destination (player's current location), so cross-dim summons are gated by the player's dim/biome, not the pet's. New `SummonResult.BANNED_DIMENSION` / `BANNED_BIOME`. Both tags ship empty.
- **Camel double-seat + llama caravans** — preserve attached llamas in a caravan when summoning the leader.
- **Pet loadouts** — save a pet's equipment layout as a named loadout, swap presets from the screen (mount-only mostly, but wolf armor counts).

---

## Tier 4 — Server / admin features

Needed once people deploy this on multiplayer.

- **Permissions** via NeoForge `PermissionAPI` nodes: `<modid>.claim`, `<modid>.summon`, `<modid>.break`, `<modid>.bypass_cooldown`, `<modid>.admin.list`, `<modid>.admin.transfer`.
- **Admin commands**:
  - `/kindred list <player>` — show bonds.
  - `/kindred transfer <bondId> <newOwner>`.
  - `/kindred revoke <bondId>` — force-remove.
  - `/kindred find <bondId>` — print last-seen dim + pos.
- **Audit log** of bond/break/summon events to a per-world log file (opt-in).
- **Per-dimension summon allow/denylist** in config.
- **Rate limit per player** to prevent spam. Distinct from per-bond cooldown.
- **Concurrent-summon cap** — limit how many pets a player can have materialized at once if you ever support multi-active pets.

---

## Tier 5 — Compatibility & integrations

- **Curios / Trinkets** — only relevant if Tier 3 adds an item.
- **JEI / EMI** — only relevant if items/recipes get added.
- **Carry On / Pickup** — ensure being carried doesn't desync ownership. (Test, don't necessarily code for.)
- **Iron's Spells & Spellbooks / Apotheosis** — saddle/armor enchantments. Probably nothing to do; just make sure NBT round-trips.
- **FTB Chunks / OpenPAC / claim mods** — query real claim API for "can I summon here". Soft-dep where possible.
- **CompactMachines / dimension-bridging mods** — make sure cross-dim summon respects their dimension types.
- **Citadel / GeckoLib pets** — verify NBT snapshot/restore preserves animation state and other extra-data slots.
- **Mod menu / Configured** — `ModConfigSpec` already integrates; just make sure category labels are translated.
- ✅ **Project MMO integration — bond-cap gate.** Soft-dep via [Project MMO](https://www.curseforge.com/minecraft/mc-mods/project-mmo). Player's effective bond cap can be gated behind a PMMO skill (default `charisma`). Two modes: `ALL_OR_NOTHING` (at start level, full `maxBonds` unlocks) and `LINEAR` (1 bond at start level, +1 every `pmmoIncrementPerBond` levels above, capped at `maxBonds`). Compat layer at `compat/pmmo/` uses an interface + reflection-bootstrapped impl so PMMO classes are never resolved when PMMO isn't installed. Effective cap ships in `S2CRosterSync` so the title-bar `X/Y` and at-capacity check both reflect it. Below start level, `ClaimResult.PMMO_LOCKED` produces a footer message like "Requires Charisma level 3" using PMMO's own `pmmo.<skill>` lang for the skill display name. Future hooks (XP awards, requirement JSON, `Perk` registration) are separate features.
- **Project MMO — additional hooks (out of v1).**
  - Award `TAMING` (or a custom `BONDING`) XP when a bond is successfully claimed.
  - Award smaller XP on summon (encourages active use) and on summon distance / cross-dim summon.
  - PMMO requirement gates: minimum skill level to claim a bond, minimum level to break, minimum level for cross-dim summon, etc. Each gate is its own config-driven hook.
  - Configurable per-entity-type XP yields via PMMO datapack JSON, in addition to defaults.
  - Player level scales `maxBonds` (e.g. +1 slot per N levels) — opt-in config.
  - Use PMMO's API at runtime via reflection or a soft-dep `ModList.get().isLoaded("pmmo")` check; never hard-link the API jar so we run fine without PMMO installed.
- **Game progression gating (KubeJS Stages / Game Stages).** Soft-dep integration so modpack authors can lock the mod behind a progression flag.
  - Config: `requiredStage: String` (default empty = no requirement). When non-empty, all bond actions (claim, summon, break, open screen) check the player has that stage. Without it, screen shows a "locked" message; keybind summon shows chat error.
  - Reference implementation: [FTB Chunks](https://github.com/FTBTeam/FTB-Mods-Issues) gates its claim/map features by stages — read their integration code for how they query stage state cleanly across both KubeJS Stages and Game Stages mods (the two have different APIs).
  - Like PMMO, soft-dep only; never hard-link.
  - Could extend to per-action stage gates (e.g. `cross_dim_stage`, `claim_stage`) if modpack authors want fine-grained control, but start with one umbrella stage.

---

## Tier 6 — Aesthetic / fun

Cheap wins that delight users.

- **Custom summon SFX per pet type** — datapack-defined.
- ❌ **Particle trail on summoned pet** — built and removed; the layered recall burst already gives enough "arrival" presence, and a multi-second trail clutters the screen and reads more like a debuff than a victory beat.
- ❌ **Recall animation** — tried a layered POOF + END_ROD column + REVERSE_PORTAL burst, reverted to vanilla single POOF for symmetry with the dismiss FX (so summon ↔ dismiss feel like counterparts). Sound switched from `PORTAL_TRIGGER` to `AMETHYST_BLOCK_CHIME` — gentler, more "pet returned" than "ender event."
- **Pet portrait in screen** — render entity head with `EntityRenderDispatcher` at scale 0.5 in the row.
- **Achievements / advancements**: bond first pet, fill the roster, summon across all dimensions, summon a pet 1000 times.
- **Stats command** — total distance traveled with each pet, summons used.

---

## Explicitly out of scope (don't build these)

- A bind item / whistle item that the player carries. The whole point of the design is the keybind+screen.
- PvP pet stealing or attacking another player's bonded pet.
- Auto-breeding from the screen.
- Anything that requires editing vanilla AI globally — only modify entities the player has bonded.

---

## Decision log

- **v1.0 = Tier 1 + most of Tier 2 polish.** All shipped items above are in. Pending Tier 2 items are nice-to-haves that don't gate v1.
- **Default keybinds**: `V` (Summon Active Pet), `G` (Open Roster). Set in [Keybinds.java](src/main/java/net/silvertide/kindred/client/input/Keybinds.java).
- **Default summon cap**: 10 (per player). Bumped from 5; rows scroll above 6 visible.
- **Mod was renamed `petsummon` → `kindred`** mid-development. Working directory is still `mount_summon/` — see CLAUDE.md note.
- See `NOTES.md` for non-obvious design decisions (saddle-flag AT, dismissed-flag rationale, scoring weights, etc.).
