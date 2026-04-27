# Pet Summon — Possible Features

Catalog of features to consider, grouped by scope. Treat tier 1 as the MVP definition; everything below is opt-in. Not a roadmap — a menu.

---

## Tier 1 — Core (MVP)

The minimum to ship.

- **Multi-pet roster**, configurable max (default 5).
- **Keybind opens a custom screen.** No items, no commands required for normal play. Default key unbound (let the user pick) to avoid conflicts.
- **Direct summon keybind** — summons the player's **active pet**, bypassing the screen. Active pet is set explicitly from the roster screen (star icon per row, click to toggle), not derived from last-summoned. Clicking Summon on a different row in the screen does not change the active pet — that's what makes "summon my dog as a one-time thing while my horse stays active" work.
  - Storage: `Optional<UUID> activePetId` on `BondRoster`. Cleared automatically when the active bond is broken or the roster goes empty. Not auto-set from first claim — leave empty until user explicitly sets one.
  - Fallback when no active is set: oldest-bonded.
  - New packet `C2SSetActivePet(Optional<UUID> bondId)` — server validates the bondId is in the player's roster; empty Optional clears.
  - `BondView` gains `boolean isActive`. `S2CRosterSync` carries it for free.
  - Rename keybind lang key `key.petsummon.summon_pet` → `key.petsummon.summon_active_pet` to match the new semantics.
- **Dismiss / unsummon.** Per-row screen button (and likely a `dismiss_pet` keybind for "dismiss most-recently-summoned"). Snapshots the pet's current state before discarding so summon restores it exactly. Eject any player riding the pet first; stop-riding any vehicle; eject other passengers. Particle + sound on discard so it reads as "recalled" instead of "vanished." Note: this lets a low-HP pet escape death — same shape as cross-dim summon's existing rescue behavior, accepted as feature not exploit. HP is preserved as-is across dismiss/summon — no free heal.
- **Claim flow**: from the screen, arm "claim next interaction"; right-click an eligible tamed pet within N seconds to bond.
- **Break-bond flow**: per-row button with two-step confirm (alchemical-style 3s arm window).
- **Summon button** per row → pet walks to you if close, teleports if far in same dim, materializes from stored NBT if in another dim or unloaded.
- **Cross-dimensional summon** with safe handling — store pet as data when its owner leaves the dim, materialize fresh on summon.
- **Anti-dupe** via per-bond revision counter in player attachment + world `SavedData`. Cancel `EntityJoinLevelEvent` for stale revisions.
- **Persistence across player death** — `AttachmentType.copyOnDeath(true)` so the roster survives respawn.
- **Bond eligibility = vanilla `OwnableEntity` + owner-match.** No allowlist needed; modded pets that implement vanilla ownership work out of the box.
- **Datapack blocklist tag** `#<modid>:bond_blocklist` — empty by default, server admins add types they don't want bondable.
- **Server-side configs** (`ModConfigSpec`): `maxBonds`, `requireSaddleable` (mount-only mode), `walkRange`, `walkSpeed`, `summonCooldownTicks`, `crossDimAllowed`, `claimWindowSeconds`, `deathIsPermanent`, `autoMount`, `requireSpace`.
- **Summon sound + particles** on summon (server-broadcast S2C ack).
- **Lang file** with all user-facing strings under the mod's namespace.

---

## Tier 2 — Polish & quality of life

Nice things that make it feel finished, but you can ship without them.

- **Rename pets** from the screen (inline edit; click name, type, save). Stored in `Bond.displayName`, syncs to entity custom name.
- **Pet preview in the menu** — render the entity model with `InventoryScreen.renderEntityInInventoryFollowsMouse`, the same path the vanilla inventory uses for the player preview. Renders the player's *actual* bonded entity (correct variant, equipment, collar, age, custom name) — not a generic species icon.
  - **Render only the expanded row** (click to expand), not every row simultaneously. Keeps cost bounded as bond count grows. Per-render cost is ~0.1–0.3ms (vanilla) or 2–10× heavier for GeckoLib/Citadel; static-vs-animated is the same cost (animation is tickCount-driven, essentially free).
  - **NBT lives in `BondView` and ships with `S2CRosterSync`.** Roster sync grows by ~5–15 KB for a typical 5-bond roster, ~50 KB worst case (chested llama with full inventory). Negligible at any realistic frequency. Avoids a separate request/response round-trip.
  - Preview entity constructed client-side via `EntityType.create(clientLevel)` + `entity.load(nbt)`. Never `addFreshEntity` — it exists only for the inventory render API, never enters the world, never ticks. Cache `Map<UUID, LivingEntity>` keyed by bondId. Cleared on screen close; cleared per-bond when the bond's revision changes (NBT may be stale).
  - Cold-cache stutter on first render of a never-seen-this-session entity type (~50–100ms texture/model load). For click-to-expand UX the stutter happens on the click — acceptable. Optional mitigation: pre-warm all preview entities on screen open if it ever becomes annoying.
  - Failure modes: `EntityType.create` returns null, NBT corrupted, entity not a `LivingEntity`, server-only type. Fall back to no preview (placeholder box with type name) rather than crashing.
  - UI shape — choose at implementation time: (a) inline expand the clicked row into a card with the model on the left; or (b) fixed preview pane on one side that displays whichever row is selected. (b) is more discoverable, (a) is more compact.
- **Quick stats** per row: speed, jump, max HP, current HP%. Computed from stored NBT. Mount-only stats (jump strength) hidden for non-mount pets.
- **Reorder rows** by drag, or with up/down buttons. Stored as a position int per bond.
- **Set primary pet** (star icon) — the direct-summon hotkey targets primary instead of last-summoned.
- **Search / filter** when many pets (input field at top, fuzzy on name + type).
- **Cooldown indicator** — radial sweep over the summon button using `g.fill` arcs while on cooldown.
- **Last-known-location** display per row — dimension and approx coords, "X seconds/minutes ago".
- **Sound packs** — datapack-overridable summon SFX per entity type.
- **Auto-mount toggle per bond** (overrides global config; only meaningful for saddleable bonds).
- **Confirm dialog when breaking a bond on a pet carrying inventory** — prevent accidental loss (chests on donkeys/llamas/mules; wolf armor; etc.).
- **Server-validated bind candidate.** Today the client raycasts and shows the Bind button for any `OwnableEntity` regardless of ownership, because `AbstractHorse` doesn't sync its owner UUID via `SynchedEntityData` (only the tamed flag bit is synced). Clicking on a wild horse shows the button, click fails with `NOT_OWNED_BY_PLAYER` chat message. Cleaner UX: when the raycasted entity changes, send `C2SCheckCandidate(entityUUID)`, server replies with `S2CCandidateResult(entityUUID, canBind, denyReason)`. Client renders Bind/disabled/hidden based on the response. One packet pair, no per-frame chatter (only fires when hover changes). Eliminates the wart entirely. Note: `TamableAnimal` (wolves, cats, parrots) *does* sync owner UUID, so the wart only affects horse-likes today.
- **Smarter spawn placement on summon.** Today the space check is a strict 3×3×3 centered on the player. Improvements:
  - Search a 5×5 (x/z) footprint around the player for a valid 3×3×3 pocket; pick the closest free spot rather than refusing if the player's exact tile is blocked.
  - Always spawn on the ground (top of a solid block within the search area), not at the player's feet level when they're mid-air.
  - Refuse summon when the player is more than ~1 block above the ground (no air-summons). Returns a new `SummonResult.PLAYER_AIRBORNE`.

---

## Tier 3 — Bigger features

Worth doing if you commit to the mod long-term.

- **Custom ownership for non-`OwnableEntity` saddleables** (pigs, striders) — a `SaddleOwner` entity attachment written when first saddled by an unowned player. Unblocks bonding pigs/striders.
- **Stable / barn block (optional)** — if you change your mind on "no items/blocks": a block that displays roster pets as live entities. Pure visual; bonds are still the source of truth. Disabled by default.
- **Equipment persistence** — saddle, armor, chest contents, wolf armor. Already covered if you snapshot full NBT, but explicitly tested as a feature.
- **Revival cost on permadeath** — when `deathIsPermanent=true`, optionally allow revival by consuming a configurable item (gold/diamond/totem/datapack-defined ingredient list).
- **XP / leveling per pet** — accumulate XP from time spent near owner / time ridden / kills assisted, unlock perks.
- **Pet perks / traits** — random or rare traits (Surefooted: no fall damage, Frostpaws: doesn't slip on ice, Beast of Burden: +chest slots, Loyal: faster summon). Datapack-defined.
- **Trust / loyalty stat** — feeding, brushing, time near owner increases loyalty; high-loyalty pets come faster, low-loyalty pets ignore the first call.
- **Multi-player bonds** — co-bond a pet to a party so both players can summon. Permission-gated.
- **Banned-zone tags** `#<modid>:no_summon_dimensions` and `#<modid>:no_summon_biomes` — datapack control over where summoning is allowed (e.g. block PvP arenas, the End fight).
- **Camel double-seat + llama caravans** — preserve attached llamas in a caravan when summoning the leader.
- **Pet loadouts** — save a pet's equipment layout as a named loadout, swap presets from the screen (mount-only mostly, but wolf armor counts).

---

## Tier 4 — Server / admin features

Needed once people deploy this on multiplayer.

- **Permissions** via NeoForge `PermissionAPI` nodes: `<modid>.claim`, `<modid>.summon`, `<modid>.break`, `<modid>.bypass_cooldown`, `<modid>.admin.list`, `<modid>.admin.transfer`.
- **Admin commands**:
  - `/petsummon list <player>` — show bonds.
  - `/petsummon transfer <bondId> <newOwner>`.
  - `/petsummon revoke <bondId>` — force-remove.
  - `/petsummon find <bondId>` — print last-seen dim + pos.
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
- **Project MMO integration.** Optional soft-dep on [Project MMO](https://www.curseforge.com/minecraft/mc-mods/project-mmo). Hooks worth exposing as PMMO XP sources / requirements:
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
- **Particle trail on summoned pet** for a few seconds (color configurable per bond).
- **Recall animation** — pet fades in with smoke or sparkles instead of popping.
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

## Decision log to fill in

When picking what to ship, note here:

- What's in v1.0?
- What's the next milestone after v1.0?
- Any features above that turned out infeasible — and why?
