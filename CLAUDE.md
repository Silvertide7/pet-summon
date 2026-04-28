# Kindred

NeoForge mod for Minecraft 1.21. A player can bond a small number of pets — wolves, cats, parrots, horses, camels, modded tameables — and recall any of them from anywhere via a custom screen opened by a keybind. There are **no items** — bonding, summoning, and management all happen through the screen and keybinds.

> **Directory note.** The repo's top-level directory is still `mount_summon/` from the original scoping. The mod ID, package, class, and lang namespace have all been renamed to `kindred`; only the working directory itself is unchanged (rename is safe but breaks any open IDE/shell sessions, so do it deliberately).

## Project metadata

- Mod ID: `kindred`
- Group / base package: `net.silvertide.kindred`
- Main class: [Kindred.java](src/main/java/net/silvertide/kindred/Kindred.java)
- Java: 21
- Minecraft: 1.21
- NeoForge: 21.0.167 (NeoForge's version scheme: `21.x.y` covers the 1.21.x line)
- Parchment: `2024.11.10` for 1.21
- Build: NeoForge ModDevGradle plugin 2.0.141, Gradle wrapper 9.2.1

## Build & run

- `./gradlew runClient` — dev client
- `./gradlew runServer` — dev server (`--nogui`)
- `./gradlew runGameTestServer` — gametest server
- `./gradlew runData` — data generators (output at `src/generated/resources/`)
- `./gradlew build` — produces `build/libs/kindred-<version>.jar`

Mod metadata lives in a **template** at [src/main/templates/META-INF/neoforge.mods.toml](src/main/templates/META-INF/neoforge.mods.toml) — `${mod_id}` etc. are expanded by the `generateModMetadata` task on every IDE sync. Edit the template, not a generated copy.

## Intent

What the mod is for, in priority order. Implementation choices should serve these.

- **The pet comes to you, reliably, from anywhere.** Cross-dimension, unloaded chunks, offline — none of these should be reasons a summon fails. Players should trust that pressing the key brings their pet.
- **Bonds survive everything that isn't intentional.** Death, respawn, dimension changes, server restarts, the pet being in unloaded chunks. The only ways to lose a bond are explicit player action (break) or, if the player opts in, pet death.
- **More than one pet.** A small configurable roster (defaults around 3–5).
- **Anything tamed by the player is fair game by default.** Vanilla `OwnableEntity` + owner-match is the bond gate. Modded pets/mounts that already implement vanilla ownership work without any per-mod configuration.
- **Server admins exclude, not include.** A datapack blocklist tag removes specific entity types from eligibility. A config flag `requireSaddleable` flips the mod into mount-only mode for servers that want that.
- **No items, no blocks, no creative tab.** Player input is keybinds plus the screen. If a feature seems to need an item, prefer redesigning the feature.
- **Server is authoritative.** The client never holds bond state of record; it renders what the server tells it.
- **The screen is custom and procedural** — built up from primitives rather than a static texture, so the layout adapts to the configured roster size and to per-row state without re-authoring art.
- **Multiplayer-safe by default.** No client trust on summon eligibility, no fake events to probe other mods, sensible cooldowns and rate limits.

## Bond gate

A bond can be claimed only when all three hold:

1. The entity implements `OwnableEntity` and `getOwnerUUID()` matches the claiming player.
2. The entity's type is **not** in `#kindred:bond_blocklist` (datapack tag, empty by default).
3. If config `requireSaddleable` is true, the entity must also implement `Saddleable`.

Pets bondable by default in vanilla: wolves, cats, parrots, horses, donkeys, mules, llamas, trader llamas, camels, skeleton/zombie horses. Pigs and Striders are intentionally out (saddleable but not ownable in vanilla); revisiting them would require a custom ownership layer and is out of scope for v1.

## Non-goals

- A bind item / whistle item that the player carries.
- Items, blocks, recipes, or a creative tab of any kind.
- Stealing or bonding another player's tamed pet.
- Custom ownership for non-`OwnableEntity` saddleables (pigs, striders) — possible later, not v1.

## References (consult when useful, don't imitate)

- [Tschipp/CallableHorses](https://github.com/Tschipp/CallableHorses/tree/1.20) — the 1.20.1 Forge mod that inspired the concept. Useful for understanding how the cross-dim / offline summon problem can be approached and what edge cases tend to bite. Its codebase is a Forge-era reference, not a blueprint; NeoForge 1.21 has better-fitting APIs (data attachments, payload registrar, etc.) and we're free to take a different path wherever it makes sense.
- [Silvertide7/alchemical](https://github.com/Silvertide7/alchemical) — example of a procedurally-drawn, dynamic NeoForge screen (no PNG backgrounds, palette-driven, eased animations). Good to read when building the pet roster screen, but it's a stylistic reference — use what fits and ignore what doesn't.
- [NeoForge docs](https://docs.neoforged.net/) — primary source of truth for APIs.
- [Parchment](https://parchmentmc.org/docs/getting-started) — mapping reference.
- [FEATURES.md](FEATURES.md) — feature catalog with shipped/pending status.
- [NOTES.md](NOTES.md) — non-obvious design decisions (bond state model, preview rendering, spawn placement scoring, networking, performance budget). Read this before extending or refactoring core systems.

## Conventions

- All gameplay/state logic runs on the server; the client only renders and dispatches input.
- Client-only code lives under `client/` and must not be classloaded on a dedicated server.
- Player-facing strings go through lang keys ([en_us.json](src/main/resources/assets/kindred/lang/en_us.json)) under the `kindred.*` / `key.*` namespaces. No hardcoded English in code.
- Configurable behavior goes through NeoForge's config system, not constants.
- Bondable scope is defined by vanilla `OwnableEntity` + owner-match, with a datapack blocklist for opt-out and a config flag for mount-only mode. Avoid hardcoding entity-type checks.
