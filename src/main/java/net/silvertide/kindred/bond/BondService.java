package net.silvertide.kindred.bond;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.silvertide.kindred.Kindred;
import net.silvertide.kindred.events.BondClaimEvent;
import net.silvertide.kindred.attachment.Bond;
import net.silvertide.kindred.attachment.BondRoster;
import net.silvertide.kindred.attachment.Bonded;
import net.silvertide.kindred.compat.pmmo.PmmoCompat;
import net.silvertide.kindred.compat.pmmo.PmmoMode;
import net.silvertide.kindred.config.Config;
import net.silvertide.kindred.data.KindredSavedData;
import net.silvertide.kindred.registry.ModAttachments;
import net.silvertide.kindred.registry.ModTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side claim / break / summon logic.
 *
 * Pure stateless logic — operates on attachments, world SavedData, and BondIndex.
 * Event wiring (snapshot triggers, revision-cancel on join, offline drain) lives in
 * separate event handlers (not yet implemented).
 */
public final class BondService {

    public enum ClaimResult {
        CLAIMED,
        NOT_OWNABLE,
        NOT_OWNED_BY_PLAYER,
        BLOCKLISTED,
        REQUIRES_SADDLEABLE,
        AT_CAPACITY,
        ALREADY_BONDED,
        NOT_ENOUGH_XP,
        PMMO_LOCKED,
        CANCELLED
    }

    public enum BreakResult {
        BROKEN,
        NO_SUCH_BOND
    }

    public enum DismissResult {
        DISMISSED,
        NO_SUCH_BOND,
        NOT_LOADED
    }

    public enum SummonResult {
        WALKING,
        TELEPORTED_NEAR,
        SUMMONED_FRESH,
        NO_SUCH_BOND,
        ON_COOLDOWN,
        GLOBAL_COOLDOWN,
        REVIVAL_PENDING,
        NO_SPACE,
        PLAYER_AIRBORNE,
        CROSS_DIM_BLOCKED,
        BANNED_DIMENSION,
        BANNED_BIOME,
        SPAWN_FAILED
    }

    /**
     * Effective bond cap for a player. Without PMMO active, this is just the
     * configured {@code maxBonds}. With PMMO active and the gate enabled, the cap
     * scales by skill level per the configured {@link PmmoMode}.
     *
     * <ul>
     *   <li>{@code skillLevel < pmmoStartLevel} → 0 (player is locked out entirely).</li>
     *   <li>{@code ALL_OR_NOTHING} above start level → full {@code maxBonds}.</li>
     *   <li>{@code LINEAR} above start level →
     *       {@code min(maxBonds, ((skillLevel - pmmoStartLevel) / pmmoIncrementPerBond) + 1)}.</li>
     * </ul>
     *
     * <p>{@code maxBonds} is always the hard ceiling — PMMO can only restrict, never
     * grant more bonds than the global cap allows.</p>
     */
    public static int effectiveMaxBonds(Player player) {
        int hardCap = Config.MAX_BONDS.get();
        if (!Config.PMMO_ENABLED.get() || !PmmoCompat.isAvailable()) return hardCap;
        long level = PmmoCompat.getSkillLevel(player, Config.PMMO_SKILL.get());
        int startLevel = Config.PMMO_START_LEVEL.get();
        if (level < startLevel) return 0;
        if (Config.PMMO_MODE.get() == PmmoMode.ALL_OR_NOTHING) return hardCap;
        int increment = Math.max(1, Config.PMMO_INCREMENT_PER_BOND.get());
        long allowed = ((level - startLevel) / increment) + 1;
        return (int) Math.min(hardCap, allowed);
    }

    /**
     * Run the same eligibility checks {@link #tryClaim} would, without mutating
     * anything. Used by the bind-candidate validation packet so the screen can
     * hide the Bind button for entities the server would refuse anyway.
     */
    public static ClaimResult checkClaimEligibility(ServerPlayer player, Entity target) {
        if (!(target instanceof OwnableEntity owned)) return ClaimResult.NOT_OWNABLE;
        if (!player.getUUID().equals(owned.getOwnerUUID())) return ClaimResult.NOT_OWNED_BY_PLAYER;
        if (BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(target.getType()).is(ModTags.CANT_BOND)) return ClaimResult.BLOCKLISTED;
        if (Config.REQUIRE_SADDLEABLE.get() && !(target instanceof Saddleable)) return ClaimResult.REQUIRES_SADDLEABLE;
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        // PMMO gate: when active, the player's effective cap depends on their skill
        // level. Cap of 0 means below the start level entirely (PMMO_LOCKED). Hitting
        // the cap with bonds already filled reuses AT_CAPACITY — message-wise that
        // reads identically to "config max" so the player always sees "Max bonds
        // reached" without us inventing PMMO-flavored copy.
        int effectiveCap = effectiveMaxBonds(player);
        if (effectiveCap == 0) return ClaimResult.PMMO_LOCKED;
        if (roster.size() >= effectiveCap) return ClaimResult.AT_CAPACITY;
        if (target.hasData(ModAttachments.BONDED.get())) return ClaimResult.ALREADY_BONDED;
        // XP gate runs last so the player sees more-specific reasons first (not yours,
        // blocklisted, etc.) instead of "save up XP" for an entity they could never
        // bond regardless of level. Creative-mode players bypass: experienceLevel
        // stays at 0 in creative but XP shouldn't be a barrier when the cost is moot.
        int xpCost = Config.BOND_XP_LEVEL_COST.get();
        if (xpCost > 0 && !player.isCreative() && player.experienceLevel < xpCost) {
            return ClaimResult.NOT_ENOUGH_XP;
        }
        return ClaimResult.CLAIMED;
    }

    public static ClaimResult tryClaim(ServerPlayer player, Entity target) {
        ClaimResult eligibility = checkClaimEligibility(player, target);
        if (eligibility != ClaimResult.CLAIMED) return eligibility;

        // External cancel hook — quest mods, datapack predicates, etc. can reject
        // the claim past our built-in gates. Posted after the cheap checks so
        // subscribers don't need to repeat ownership/blocklist/cap logic.
        BondClaimEvent event = new BondClaimEvent(player, target);
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) return ClaimResult.CANCELLED;

        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        ServerLevel level = (ServerLevel) target.level();
        KindredSavedData saved = KindredSavedData.get(level);

        UUID bondId = UUID.randomUUID();
        int revision = saved.incrementRevision(bondId);

        CompoundTag snapshot = target.saveWithoutId(new CompoundTag());
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        long now = System.currentTimeMillis();

        // If the pet was already nametagged before binding, carry that name through as
        // the bond's display name so the roster and any future rename UI start from it.
        Optional<String> initialName = Optional.ofNullable(target.getCustomName())
                .map(Component::getString)
                .filter(s -> !s.isEmpty());

        Bond bond = new Bond(
                bondId,
                typeId,
                snapshot,
                level.dimension(),
                target.position(),
                revision,
                initialName,
                now,
                0L,
                Optional.empty(),
                false  // newly bonded entity is in the world
        );

        // First bond claimed becomes active automatically. Subsequent claims keep the
        // existing active. Keeps the invariant: bonds non-empty ⇒ active is set.
        BondRoster newRoster = roster.with(bond);
        if (newRoster.activePetId().isEmpty()) {
            newRoster = newRoster.withActive(Optional.of(bondId));
        }
        player.setData(ModAttachments.BOND_ROSTER.get(), newRoster);
        target.setData(ModAttachments.BONDED.get(), new Bonded(bondId, player.getUUID(), revision));
        BondIndex.get().track(bondId, target);

        // Charge XP after the attachment writes so a failure mid-claim wouldn't leave
        // the player out the levels without the bond. Creative-mode skip mirrors the
        // eligibility gate above. giveExperienceLevels accepts a negative delta.
        int xpCost = Config.BOND_XP_LEVEL_COST.get();
        if (xpCost > 0 && !player.isCreative()) {
            player.giveExperienceLevels(-xpCost);
        }

        Kindred.LOGGER.info("[kindred] {} claimed bond {} on {}", player.getGameProfile().getName(), bondId, typeId);
        return ClaimResult.CLAIMED;
    }

    public static BreakResult breakBond(ServerPlayer player, UUID bondId) {
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        Optional<Bond> maybeBond = roster.get(bondId);
        if (maybeBond.isEmpty()) return BreakResult.NO_SUCH_BOND;

        ServerLevel level = (ServerLevel) player.level();
        KindredSavedData saved = KindredSavedData.get(level);

        // Only materialize-before-break when the pet was dismissed via the screen
        // (i.e. only the snapshot exists, no chunk-saved entity). For pets just sitting
        // in an unloaded chunk somewhere — at the player's home base, say — leave them
        // where they are; teleporting to a player who's about to break the bond could
        // strand them in a dangerous spot with no way to recall.
        Optional<Entity> existing = BondIndex.get().find(bondId);
        if (existing.isEmpty() && maybeBond.get().dismissed()) {
            materializeFresh(player, maybeBond.get(), level, saved);
            existing = BondIndex.get().find(bondId);
        }

        // Re-read roster — materializeFresh updates the bond's revision/timestamps
        // before we strip it.
        BondRoster current = player.getData(ModAttachments.BOND_ROSTER.get());
        player.setData(ModAttachments.BOND_ROSTER.get(), current.without(bondId));

        if (existing.isPresent()) {
            Entity entity = existing.get();
            entity.removeData(ModAttachments.BONDED.get());
            BondIndex.get().untrack(bondId);
            saved.clearBond(bondId);
        } else {
            // Entity not loaded and materialize failed — wipe what we can, and queue
            // the entity-side cleanup so its Bonded attachment is removed the next
            // time it loads.
            saved.clearBond(bondId);
            saved.markPendingDisband(bondId);
        }

        Kindred.LOGGER.info("[kindred] {} broke bond {}", player.getGameProfile().getName(), bondId);
        return BreakResult.BROKEN;
    }

    /**
     * Recall a loaded bonded entity back to bond storage. Snapshots the entity's
     * current state (via the leave-event handler that fires from {@code discard()}),
     * ejects passengers and stops riding, plays a "poof" effect, then discards.
     *
     * HP is preserved as-is — dismissing a low-HP pet means it returns at low HP.
     * No free heal. Same exploit shape as cross-dim summon's existing rescue path,
     * accepted as a feature.
     */
    public static DismissResult dismiss(ServerPlayer player, UUID bondId) {
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        if (roster.get(bondId).isEmpty()) return DismissResult.NO_SUCH_BOND;

        Optional<Entity> existing = BondIndex.get().find(bondId);
        if (existing.isEmpty()) return DismissResult.NOT_LOADED;
        Entity entity = existing.get();

        // Eject any passengers (player riding the pet, etc.) and break out of any vehicle.
        entity.ejectPassengers();
        if (entity.isPassenger()) entity.stopRiding();

        ServerLevel entityLevel = (ServerLevel) entity.level();
        double cx = entity.getX();
        double cy = entity.getY() + entity.getBbHeight() / 2.0D;
        double cz = entity.getZ();

        entityLevel.sendParticles(ParticleTypes.POOF, cx, cy, cz,
                20, 0.3D, 0.3D, 0.3D, 0.05D);
        entityLevel.playSound(null, cx, cy, cz,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.3F, 1.2F);

        // discard() synchronously fires EntityLeaveLevelEvent, which our handler uses
        // to snapshot the bond and untrack from BondIndex. No need to repeat that here.
        entity.discard();

        // Mark the bond as snapshot-only so a later breakBond knows to materialize
        // before clearing — distinguishes this from "in an unloaded chunk" where the
        // entity still exists and would be teleported to the player by mistake.
        BondRoster post = player.getData(ModAttachments.BOND_ROSTER.get());
        post.get(bondId).ifPresent(b -> player.setData(ModAttachments.BOND_ROSTER.get(),
                post.with(b.withDismissed(true))));

        Kindred.LOGGER.info("[kindred] {} dismissed bond {}", player.getGameProfile().getName(), bondId);
        return DismissResult.DISMISSED;
    }

    public static SummonResult summon(ServerPlayer player, UUID bondId) {
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        Optional<Bond> maybeBond = roster.get(bondId);
        if (maybeBond.isEmpty()) return SummonResult.NO_SUCH_BOND;
        Bond bond = maybeBond.get();

        long now = System.currentTimeMillis();
        long cooldownMs = Config.SUMMON_COOLDOWN_TICKS.get() * 50L;
        if (now - bond.lastSummonedAt() < cooldownMs) return SummonResult.ON_COOLDOWN;

        // Per-bond revival cooldown (after non-permanent death).
        long revivalCooldownMs = Config.revivalCooldownMs();
        if (revivalCooldownMs > 0L && bond.diedAt().isPresent()) {
            long diedAt = bond.diedAt().get();
            if (now - diedAt < revivalCooldownMs) return SummonResult.REVIVAL_PENDING;
        }

        // Roster-wide spam cooldown.
        long globalCooldownMs = Config.summonGlobalCooldownMs();
        if (GlobalSummonCooldownTracker.get().remainingMs(player.getUUID(), globalCooldownMs) > 0L) {
            return SummonResult.GLOBAL_COOLDOWN;
        }

        // Airborne players can't summon — pet would either fall or path to an unreachable
        // target. Applies to all summon paths (walk and materialize).
        if (Config.REQUIRE_SPACE.get() && !isPlayerGrounded(player)) return SummonResult.PLAYER_AIRBORNE;

        ServerLevel playerLevel = (ServerLevel) player.level();
        KindredSavedData saved = KindredSavedData.get(playerLevel);
        ResourceKey<Level> playerDim = playerLevel.dimension();

        // Datapack-controlled summon zones. Checks the *destination* (where the pet
        // would materialize), so cross-dim summons are gated by the player's current
        // location, not the pet's. Tag membership is auto-synced to clients in vanilla,
        // but we don't bother pre-validating client-side — the chat error is fine.
        if (playerLevel.dimensionTypeRegistration().is(ModTags.NO_SUMMON_DIMENSIONS)) {
            return SummonResult.BANNED_DIMENSION;
        }
        if (playerLevel.getBiome(player.blockPosition()).is(ModTags.NO_SUMMON_BIOMES)) {
            return SummonResult.BANNED_BIOME;
        }

        Optional<Entity> existing = BondIndex.get().find(bondId);
        if (existing.isPresent()) {
            Entity old = existing.get();
            ResourceKey<Level> oldDim = old.level().dimension();

            if (oldDim.equals(playerDim)) {
                double dx = old.getX() - player.getX();
                double dz = old.getZ() - player.getZ();
                double distSq = dx * dx + dz * dz;
                double walkRange = Config.WALK_RANGE.get();

                if (distSq <= walkRange * walkRange && old instanceof Mob mob) {
                    wake(old);
                    mob.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), Config.WALK_SPEED.get());
                    playSummonFx(playerLevel, player.getX(), player.getY(), player.getZ(), false);
                    writeSummonTimestamp(player, bond, roster);
                    GlobalSummonCooldownTracker.get().recordSummon(player.getUUID());
                    return SummonResult.WALKING;
                }
                // Same dim but far. Entity.teleportTo doesn't reliably re-register the
                // entity with the chunk manager / player tracker when crossing into the
                // player's view from outside, so the client can miss the move entirely.
                // Discard + materialize fresh routes through addFreshEntity, which is
                // robust. UUID continuity is preserved via the NBT snapshot.
                return discardAndMaterialize(player, old, bond, playerLevel, saved, SummonResult.TELEPORTED_NEAR);
            }

            // Cross-dim
            if (!Config.CROSS_DIM_ALLOWED.get()) return SummonResult.CROSS_DIM_BLOCKED;
            return discardAndMaterialize(player, old, bond, playerLevel, saved, SummonResult.SUMMONED_FRESH);
        }

        // Not loaded anywhere — materialize from stored snapshot
        return materializeFresh(player, bond, playerLevel, saved);
    }

    private static SummonResult discardAndMaterialize(ServerPlayer player, Entity old, Bond bond, ServerLevel playerLevel, KindredSavedData saved, SummonResult successResult) {
        UUID bondId = bond.bondId();
        CompoundTag freshNbt = old.saveWithoutId(new CompoundTag());
        old.discard();
        BondIndex.get().untrack(bondId, old);
        Bond refreshed = bond.withSnapshot(freshNbt, playerLevel.dimension(), player.position());
        SummonResult result = materializeFresh(player, refreshed, playerLevel, saved);
        return result == SummonResult.SUMMONED_FRESH ? successResult : result;
    }

    private static SummonResult materializeFresh(ServerPlayer player, Bond bond, ServerLevel targetLevel, KindredSavedData saved) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(bond.entityType());
        if (type == null) return SummonResult.SPAWN_FAILED;

        // Find a valid spawn pocket within 5x5 of the player; snap to ground.
        Vec3 spawnPos;
        if (Config.REQUIRE_SPACE.get()) {
            Optional<Vec3> found = findSpawnLocation(targetLevel, player, type.getDimensions());
            if (found.isEmpty()) return SummonResult.NO_SPACE;
            spawnPos = found.get();
        } else {
            spawnPos = player.position();
        }

        Entity entity = type.create(targetLevel);
        if (entity == null) return SummonResult.SPAWN_FAILED;

        entity.load(bond.nbtSnapshot());

        // bond.displayName is the source of truth for the pet's name; the snapshot's
        // CustomName might lag behind (e.g. rename happened while pet was offline).
        applyDisplayName(entity, bond.displayName());

        // Snapshots taken from a dead entity have health=0 plus whatever transient
        // status caused the death (fire ticks, active effects, etc.). On revival,
        // freshen all of it so the pet doesn't reappear mid-burn / mid-poison.
        if (!Config.DEATH_IS_PERMANENT.get() && entity instanceof LivingEntity living && living.getHealth() <= 0) {
            freshenForRevival(living);
        }

        // If the snapshot captured a sitting wolf/cat/etc., stand them up — the player
        // just summoned them, they're not supposed to plop into a sit pose on arrival.
        wake(entity);

        entity.setPos(spawnPos.x, spawnPos.y, spawnPos.z);

        int newRevision = saved.incrementRevision(bond.bondId());
        entity.setData(ModAttachments.BONDED.get(), new Bonded(bond.bondId(), player.getUUID(), newRevision));

        // addFreshEntity fires EntityJoinLevelEvent, which our handler responds to by tracking
        // in BondIndex. No explicit track needed here.
        if (!targetLevel.addFreshEntity(entity)) return SummonResult.SPAWN_FAILED;

        playSummonFx(targetLevel, spawnPos.x, spawnPos.y, spawnPos.z, true);

        // Re-read roster: the cross-dim path discards the old entity above, which fires the
        // leave event synchronously and writes a snapshot into the player's roster. Trusting
        // a roster captured before that point would silently drop the leave-event's write.
        BondRoster currentRoster = player.getData(ModAttachments.BOND_ROSTER.get());
        Bond updated = bond.withRevision(newRevision)
                .withLastSummonedAt(System.currentTimeMillis())
                .withDiedAt(Optional.empty())  // successful materialize IS the revival
                .withDismissed(false);         // entity is back in the world
        player.setData(ModAttachments.BOND_ROSTER.get(), currentRoster.with(updated));

        GlobalSummonCooldownTracker.get().recordSummon(player.getUUID());

        return SummonResult.SUMMONED_FRESH;
    }

    /**
     * Sync an entity's in-world {@code customName} (and visibility) with the bond's
     * display name. Empty bond name clears the nametag entirely. Mirrors what a
     * vanilla nametag item would do, so renamed pets show their name floating above.
     */
    public static void applyDisplayName(Entity entity, Optional<String> displayName) {
        if (displayName.isPresent()) {
            entity.setCustomName(Component.literal(displayName.get()));
            entity.setCustomNameVisible(true);
        } else {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
        }
    }

    /**
     * Reset transient post-death state on revival. Health back to max; fire, active
     * potion effects, freeze ticks, fall distance, and air supply all cleared so the
     * pet reappears in a clean state instead of mid-burn or mid-poison.
     */
    private static void freshenForRevival(LivingEntity living) {
        living.setHealth(living.getMaxHealth());
        living.clearFire();
        living.removeAllEffects();
        living.setAirSupply(living.getMaxAirSupply());
        living.fallDistance = 0F;
        living.setTicksFrozen(0);
    }

    /**
     * Plays the summon FX at the given location. {@code withParticles} is true on
     * materialize paths (entity appears at the player) and false on the walk path
     * (entity is already in the world, just being told to come). Mirrors the
     * dismiss FX shape — single POOF burst — so summon and dismiss feel like
     * symmetric counterparts. Sound is amethyst chime: gentle, magical, and
     * distinct from any combat or ambient sound.
     */
    private static void playSummonFx(ServerLevel level, double x, double y, double z, boolean withParticles) {
        if (withParticles) {
            level.sendParticles(ParticleTypes.POOF, x, y + 0.5D, z, 20, 0.3D, 0.3D, 0.3D, 0.05D);
        }
        level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.5F, 1.0F);
    }

    /**
     * Clear sitting state on TamableAnimal (wolf, cat, parrot). A sitting tame's AI
     * blocks pathfinding while position updates still happen, producing the "boot-scoot"
     * slide when summoned. AbstractHorse doesn't have sitting, so this is a no-op for
     * horse-likes. Run before navigation.moveTo and after entity.load(snapshot).
     */
    private static void wake(Entity entity) {
        if (entity instanceof TamableAnimal tame) {
            tame.setOrderedToSit(false);
            tame.setInSittingPose(false);
        }
    }

    private static void writeSummonTimestamp(ServerPlayer player, Bond bond, BondRoster roster) {
        // Successful summon also clears any pending revival cooldown — this IS the revival.
        Bond updated = bond.withLastSummonedAt(System.currentTimeMillis()).withDiedAt(Optional.empty());
        player.setData(ModAttachments.BOND_ROSTER.get(), roster.with(updated));
    }

    /**
     * "Grounded" tolerance: the player is grounded if {@link Entity#onGround()} or there's
     * a sturdy block within ~2 blocks below their feet. Lets a small jump or step-down
     * succeed; refuses high-altitude flying.
     */
    private static boolean isPlayerGrounded(ServerPlayer player) {
        if (player.onGround()) return true;
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos check = feet.below(dy);
            BlockState state = level.getBlockState(check);
            if (state.isFaceSturdy(level, check, Direction.UP)) return true;
        }
        return false;
    }

    /**
     * Search a 5x5 (x/z) footprint around the player for a spot where the entity can
     * stand without overlapping any block. Candidates are sorted by a combined
     * distance + facing-direction score so the pet prefers landing on flat ground in
     * front of the player. The player's own tile (dx=0, dz=0) is the last resort —
     * we'd rather have the pet beside or in front of the player than on top of them.
     *
     * Each column tries 1 block above the player's feet (handles a single-block step
     * up in front), the player's level, and up to 3 below (handles step-downs and
     * overhangs). "Floor must be sturdy" + "pocket must be free of collisions" are
     * the only structural checks — dropoffs adjacent to the spot are fine.
     */
    private static Optional<Vec3> findSpawnLocation(ServerLevel level, ServerPlayer player, EntityDimensions dims) {
        BlockPos pp = player.blockPosition();

        // Horizontal forward unit vector from the player's yaw. -sin/cos because
        // Minecraft yaw 0 looks toward +Z and increases clockwise.
        float yawRad = player.getYRot() * (float) (Math.PI / 180.0);
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);

        record Candidate(int dx, int dz, double score) {}
        List<Candidate> ranked = new ArrayList<>(24);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue; // on-player handled as last resort
                double forwardness = dx * fx + dz * fz;       // + in front, - behind
                double lateral = Math.abs(dx * fz - dz * fx); // perpendicular distance
                double dist = Math.sqrt(dx * dx + dz * dz);
                // Higher = better. Forwardness dominates direction (front >> behind),
                // lateral lightly penalizes off-axis spots so direct-front beats the
                // diagonal corners, and the small +dist nudge breaks ties toward
                // landing a couple blocks out instead of right next to the player.
                ranked.add(new Candidate(dx, dz, forwardness - 0.2 * lateral + 0.05 * dist));
            }
        }
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed());

        // Two-pass: prefer dry footprints across the entire 5x5 area before settling
        // for a wet one. A pet shouldn't materialize in the pond when there's a
        // patch of grass two tiles further from the player.
        for (Candidate c : ranked) {
            Optional<Vec3> spot = tryColumn(level, pp.offset(c.dx, 0, c.dz), dims, false);
            if (spot.isPresent()) return spot;
        }
        Optional<Vec3> dryOnPlayer = tryColumn(level, pp, dims, false);
        if (dryOnPlayer.isPresent()) return dryOnPlayer;

        for (Candidate c : ranked) {
            Optional<Vec3> spot = tryColumn(level, pp.offset(c.dx, 0, c.dz), dims, true);
            if (spot.isPresent()) return spot;
        }
        return tryColumn(level, pp, dims, true);
    }

    private static Optional<Vec3> tryColumn(ServerLevel level, BlockPos start, EntityDimensions dims, boolean allowWater) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        // dy = -1 is one block above the player (handles a step-up), 0 is player level,
        // 1..3 are step-downs/overhangs. We iterate top-down so the highest valid floor
        // wins (avoids burrowing into a hole when the pet could stand on the lip above).
        for (int dy = -1; dy <= 3; dy++) {
            int feetY = start.getY() - dy;
            if (feetY <= minY || feetY >= maxY) continue;
            BlockPos top = new BlockPos(start.getX(), feetY, start.getZ());
            BlockPos floor = top.below();
            BlockState floorState = level.getBlockState(floor);
            if (!floorState.isFaceSturdy(level, floor, Direction.UP)) continue;
            if (isHazardousFloor(floorState)) return Optional.empty();
            AABB box = dims.makeBoundingBox(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D);
            if (!level.noCollision(box)) {
                // Sturdy floor here but the pet's bounding box hits something above it.
                // Don't keep looking deeper in this column — anything below is buried.
                return Optional.empty();
            }
            // Lava has no collision shape, so noCollision passes through it. Reject any
            // candidate whose footprint OR a 1-block horizontal buffer contains lava.
            // Buffer guards against landing right beside a lava stream the pet could
            // get pushed into, or that would burn the pet from adjacent.
            if (hasLavaInOrNear(level, box)) return Optional.empty();
            // Water is fine for most pets but ugly when the player is standing on shore.
            // findSpawnLocation runs a dry-only pass first; this column is rejected then
            // and revisited on the second pass with allowWater=true.
            if (!allowWater && hasFluidInPocket(level, box, FluidTags.WATER)) return Optional.empty();
            return Optional.of(new Vec3(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D));
        }
        return Optional.empty();
    }

    /**
     * Floor blocks that are face-sturdy but immediately damage anything standing on
     * them. Pre-existing isFaceSturdy gate already filters non-solid floors (lava,
     * water, air); this catches the dry-but-burning floors.
     */
    private static boolean isHazardousFloor(BlockState state) {
        var block = state.getBlock();
        if (block == Blocks.MAGMA_BLOCK) return true;
        if (block == Blocks.FIRE || block == Blocks.SOUL_FIRE) return true;
        if ((block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE)
                && state.getValue(CampfireBlock.LIT)) return true;
        return false;
    }

    /**
     * True if any block within the entity's footprint or a 1-block horizontal buffer
     * around it contains lava (source or flowing). Vertical buffer is 0 — lava
     * directly above is a rare edge case and the floor below is already gated by
     * {@link #isHazardousFloor} (lava isn't face-sturdy, so it can't be a floor).
     */
    private static boolean hasLavaInOrNear(ServerLevel level, AABB box) {
        AABB scan = box.inflate(1.0D, 0.0D, 1.0D);
        BlockPos min = BlockPos.containing(scan.minX, scan.minY, scan.minZ);
        BlockPos max = BlockPos.containing(scan.maxX - 1.0E-7D, scan.maxY - 1.0E-7D, scan.maxZ - 1.0E-7D);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (level.getFluidState(p).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    /**
     * True if any block within the entity's footprint contains the given fluid (no
     * horizontal buffer). Used to bias spawn placement toward dry land first.
     */
    private static boolean hasFluidInPocket(ServerLevel level, AABB box, net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> fluidTag) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX - 1.0E-7D, box.maxY - 1.0E-7D, box.maxZ - 1.0E-7D);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (level.getFluidState(p).is(fluidTag)) return true;
        }
        return false;
    }

    private BondService() {}
}
