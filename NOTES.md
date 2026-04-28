# Kindred — Design Notes

Non-obvious design decisions and the reasoning behind them. Feature catalog lives in [FEATURES.md](FEATURES.md); project metadata in [CLAUDE.md](CLAUDE.md). This file documents *why* certain code paths look the way they do.

---

## Bond state model

### `Bond.dismissed` flag

Distinguishes two cases that look identical to `BondIndex.find()` (both return empty):

1. **Pet in an unloaded chunk** — entity exists in chunk-saved data on disk. Will load with the chunk. `dismissed = false`.
2. **Pet dismissed via the screen** — entity was `discard()`ed, only the snapshot in `BondRoster` exists. No chunk version. `dismissed = true`.

Set true in `BondManager.dismiss()` after `entity.discard()`, cleared in `materializeFresh()` and at claim time. `withDismissed(boolean)` helper for record updates.

**Why we need it**: `breakBond` only materializes-before-break when `dismissed=true`. For pets sitting at home in unloaded chunks, breaking the bond should leave them where they are; teleporting them to the player would strand them somewhere dangerous with no recall.

### `Bond.diedAt` (revival cooldown)

`Optional<Long>` set in `LivingDeathEvent` handler when `deathIsPermanent=false` and `revivalCooldownSeconds > 0`. Cleared on successful materialize (the revival itself). Drives `SummonResult.REVIVAL_PENDING` and the "Respawning Xs" UI.

### `BondView.loaded` (wire field)

Server checks `BondIndex.find(...).isPresent()` per bond at sync time, ships as a boolean in `BondView`. Drives:
- "Horse · Overworld" vs "Horse · Resting" subtitle
- Dismiss button enabled vs disabled

Cheap — `BondIndex` is a `ConcurrentHashMap<UUID, Entity>` maintained incrementally via `EntityJoinLevelEvent` / `EntityLeaveLevelEvent`. No scanning.

---

## Preview rendering

### Saddle requires an access transformer

In MC 1.21.0, horse saddles live in a separate inventory slot (`SaddleItem` in NBT) but the renderer reads a flag bit (`DATA_ID_FLAGS` bit 4) in `SynchedEntityData` to decide whether to draw the saddle. That flag is set server-side by `AbstractHorse.updateContainerEquipment`, which is no-op'd on the client. Our preview entity never joins a server-synced world, so the bit stays unset and the saddle doesn't render.

**Fix**: `META-INF/accesstransformer.cfg` opens `AbstractHorse.setFlag(IZ)V`. After `entity.load(snapshot)`, `freshenForPreview` checks for `SaddleItem` in the NBT and calls `horse.setFlag(4, saddled)` manually.

**Migration note**: 1.21.5+ unified equipment slots — saddles moved into a real `EquipmentSlot.SADDLE`. When this mod ever updates past 1.21.0, this AT entry and the `setFlag` call become unnecessary. Delete both at that point.

Body armor doesn't have this problem — it's in the standard `BODY` equipment slot, rendered directly without a flag.

### Preview cache invalidation

`PreviewEntityCache` keys by `bondId`. Two clear points:
1. **`onRosterSync`** — every incoming sync clears the cache. Necessary because the server may have just captured fresh live NBT (saddle/armor/chest/age/etc.) for a loaded pet, and the cached `LivingEntity` was built from the *previous* snapshot.
2. **`Screen.removed()`** — when the roster screen closes.

Cost: each rebuild is a `EntityType.create` + `entity.load(nbt)`. First build per type per session loads textures/models (~50–100ms); subsequent builds reuse cached models (~ms).

### Live NBT capture at sync time

`ServerPacketHandler.sendRosterSync` checks `BondIndex.find(bondId)` per bond. If loaded, captures fresh NBT via `live.saveWithoutId(new CompoundTag())`. Otherwise uses the stored snapshot.

This is what makes saddle/armor/breeding-age/chest-contents updates flow into the preview without waiting for a chunk unload. Cost is ~50µs–1ms per loaded pet per sync; sync fires only on screen open and bond mutations, so total impact is unmeasurable.

### Mouse-tilt clamp

`renderEntityInInventoryFollowsMouse` derives entity pitch from `(centerY − mouseY) / 40`. With the cursor over the row list (above the preview pane), pitch goes high enough that the head crops against the top of the box.

We clamp the `mouseY` we feed the renderer to `centerY ± 8`, so pitch is bounded to a small range. Yaw still follows the actual cursor for some life.

### Scale multiplier 0.5

Tall mob models (horse, llama, camel) extend ~30% above their bbHeight. The width-fit cap of 60 was leaving the head clipped. Multiplier on the height-fit `paneH * 0.5F / bbHeight` scales tall mobs down to fit; small mobs (wolf, cat) are width-bound and still hit 60.

### Nametag hidden in preview

`freshenForPreview` calls `living.setCustomNameVisible(false)`. The roster row's name is rendered separately from `bond.displayName()`, so hiding the floating nametag in the preview keeps the model cleaner. The custom name on the live in-world entity is unaffected (different entity instance).

---

## Spawn placement scoring

`BondManager.findSpawnLocation` builds a 5×5 grid of `(dx, dz)` candidates around the player (excluding the player's own tile) and ranks them by:

```
score = forwardness − 0.2 × lateral + 0.05 × dist
```

Where:
- `forwardness = dx·fx + dz·fz` (positive = in front of player, negative = behind)
- `lateral = |dx·fz − dz·fx|` (perpendicular distance from forward axis)
- `dist = sqrt(dx² + dz²)`

Higher score wins. The forwardness term dominates direction (front beats behind by ~4 points); the lateral penalty pulls the choice toward direct-front over diagonals; the small distance bonus prefers ~2 blocks out over right-on-top-of-player.

The player's own tile is the **last resort** — only attempted if all 24 ranked candidates fail their column check.

Column search runs `dy = -1..3`: one block above (handles a 1-block step-up in front), the player's level, and three blocks below (handles overhangs / step-downs).

---

## Performance budget reference

For "is X worth the cost?" decisions later:

| Operation | Cost | Frequency |
|---|---|---|
| `BondIndex.find(uuid)` | ~10–50ns | Per sync, per bond |
| `entity.saveWithoutId(...)` (live NBT capture) | 50µs–1ms | Per sync, per loaded bond |
| `EntityType.create + entity.load` (preview build) | 50–100ms first time per type, ~1ms after | On-demand, cached |
| `setFlag(4, ...)` (saddle flag fix) | ~100ns | Per preview build |
| `findSpawnLocation` (5×5 ranked search + column checks) | 10µs–1ms | Per summon attempt |
| `S2CRosterSync` packet | ~5–50KB depending on bond count + chest contents | Per screen open + per bond mutation |

Server tick budget: 50ms. Most of these are well under 0.1% of one tick.

---

## Networking

### Packets

| Direction | Packet | Purpose |
|---|---|---|
| C2S | `C2SOpenRoster` | Trigger an initial roster sync |
| C2S | `C2SSummonByKeybind` | Summon active pet via the V key |
| C2S | `C2SSummonBond` | Summon a specific bond (button click) |
| C2S | `C2SBreakBond` | Break a bond |
| C2S | `C2SDismissBond` | Dismiss a specific bond |
| C2S | `C2SClaimEntity` | Bind a raycasted entity |
| C2S | `C2SCheckBindCandidate` | "Is this entity bindable?" check on screen open |
| C2S | `C2SSetActivePet` | Set/clear active bond |
| C2S | `C2SRenameBond` | Rename a bond's display name |
| S2C | `S2CRosterSync` | Full roster + global cooldown remaining + per-bond live NBT |
| S2C | `S2CCancelHold` | Cancel any in-progress hold (damage interrupt) |
| S2C | `S2CBindCandidateResult` | Verdict on `C2SCheckBindCandidate` (canBind + optional deny lang key) |

### Why responses echo the entity UUID

`S2CBindCandidateResult` carries the request's `entityUUID` so the client can ignore stale responses. Same pattern would apply to any future request/reply packets where the player might issue multiple in flight (rapid screen open/close, etc.).

### Cooldown sync without clock skew

`S2CRosterSync` ships *remaining ms at send time*, and the client measures elapsed-since-receive. No reliance on synchronized clocks. Same pattern for both per-bond cooldown and global cooldown.

---

## Conventions to keep

- **Server is authoritative.** Client never holds bond state of record. `BondView` is a render-only projection.
- **Lang keys for all user-facing strings.** `kindred.*` and `key.kindred.*` namespaces. No hardcoded English in Java.
- **No items, no blocks, no recipes.** Keybinds + screen only. If a feature seems to need an item, redesign first.
- **Configurable behavior goes through `Config` (`ModConfigSpec`)**, not constants. Configs are server-side; sync to client automatically on world join.
- **Bondable scope = `OwnableEntity` + owner-match.** Datapack blocklist for opt-out, `requireSaddleable` config for mount-only mode. Don't hardcode entity-type checks.
- **Time configs in seconds for users.** Internal math in milliseconds via `Config.holdToDismissMs()` etc. helpers.

---

## Things that might surprise you

- **Working directory is still `mount_summon/`** — pre-rename name. Mod ID, package, class, lang namespace are all `kindred`.
- **`PreviewEntityCache` lives only on the client.** Never construct entities in `mc.level` for any reason other than the preview render. They never `addFreshEntity`, never tick, never persist.
- **`BondManager.tryClaim` first-bond auto-active**: when a player claims their first bond, it's automatically set as active. Subsequent claims keep the existing active. Maintains the invariant "bonds non-empty ⇒ active is set."
- **`Bonded` attachment on the entity** is separate from `BondRoster` on the player. Both must be kept in sync. `BondIndex` is a runtime lookup that doesn't persist — rebuilt incrementally via join/leave events.
- **`PetSummonSavedData` was renamed to `KindredSavedData`** — the world-saved data file at `<world>/data/<name>.dat`. Old `petsummon-*` save files won't be read.
- **Existing config files don't migrate.** New file is `<world>/serverconfig/kindred-server.toml`. Defaults regenerate; admins re-tune values.
