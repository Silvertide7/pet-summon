package net.silvertide.petsummon.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.attachment.Bond;
import net.silvertide.petsummon.attachment.BondRoster;
import net.silvertide.petsummon.attachment.Bonded;
import net.silvertide.petsummon.config.Config;
import net.silvertide.petsummon.registry.ModAttachments;
import net.silvertide.petsummon.registry.ModTags;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-side claim / break / summon logic.
 *
 * Pure stateless logic — operates on attachments, world SavedData, and BondIndex.
 * Event wiring (snapshot triggers, revision-cancel on join, offline drain) lives in
 * separate event handlers (not yet implemented).
 */
public final class BondManager {

    public enum ClaimResult {
        CLAIMED,
        NOT_OWNABLE,
        NOT_OWNED_BY_PLAYER,
        BLOCKLISTED,
        REQUIRES_SADDLEABLE,
        AT_CAPACITY,
        ALREADY_BONDED
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
        NO_SPACE,
        PLAYER_AIRBORNE,
        CROSS_DIM_BLOCKED,
        SPAWN_FAILED
    }

    public static ClaimResult tryClaim(ServerPlayer player, Entity target) {
        if (!(target instanceof OwnableEntity owned)) return ClaimResult.NOT_OWNABLE;
        if (!player.getUUID().equals(owned.getOwnerUUID())) return ClaimResult.NOT_OWNED_BY_PLAYER;

        if (BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(target.getType()).is(ModTags.BOND_BLOCKLIST)) return ClaimResult.BLOCKLISTED;

        if (Config.REQUIRE_SADDLEABLE.get() && !(target instanceof Saddleable)) return ClaimResult.REQUIRES_SADDLEABLE;

        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        if (roster.size() >= Config.MAX_BONDS.get()) return ClaimResult.AT_CAPACITY;

        if (target.hasData(ModAttachments.BONDED.get())) return ClaimResult.ALREADY_BONDED;

        ServerLevel level = (ServerLevel) target.level();
        PetSummonSavedData saved = PetSummonSavedData.get(level);

        UUID bondId = UUID.randomUUID();
        int revision = saved.incrementRevision(bondId);

        CompoundTag snapshot = target.saveWithoutId(new CompoundTag());
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        long now = System.currentTimeMillis();

        Bond bond = new Bond(
                bondId,
                typeId,
                snapshot,
                level.dimension(),
                target.position(),
                revision,
                Optional.empty(),
                now,
                0L
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

        PetSummon.LOGGER.info("[petsummon] {} claimed bond {} on {}", player.getGameProfile().getName(), bondId, typeId);
        return ClaimResult.CLAIMED;
    }

    public static BreakResult breakBond(ServerPlayer player, UUID bondId) {
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        if (roster.get(bondId).isEmpty()) return BreakResult.NO_SUCH_BOND;

        player.setData(ModAttachments.BOND_ROSTER.get(), roster.without(bondId));

        ServerLevel level = (ServerLevel) player.level();
        PetSummonSavedData saved = PetSummonSavedData.get(level);

        Optional<Entity> existing = BondIndex.get().find(bondId);
        if (existing.isPresent()) {
            Entity entity = existing.get();
            entity.removeData(ModAttachments.BONDED.get());
            BondIndex.get().untrack(bondId);
            saved.clearBond(bondId);
        } else {
            // Entity not loaded — wipe what we can, and queue the entity-side cleanup
            // so its Bonded attachment is removed the next time it loads.
            saved.clearBond(bondId);
            saved.markPendingDisband(bondId);
        }

        PetSummon.LOGGER.info("[petsummon] {} broke bond {}", player.getGameProfile().getName(), bondId);
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
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.6F, 1.2F);

        // discard() synchronously fires EntityLeaveLevelEvent, which our handler uses
        // to snapshot the bond and untrack from BondIndex. No need to repeat that here.
        entity.discard();

        PetSummon.LOGGER.info("[petsummon] {} dismissed bond {}", player.getGameProfile().getName(), bondId);
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

        // Roster-wide spam cooldown.
        long globalCooldownMs = Config.SUMMON_GLOBAL_COOLDOWN_MS.get();
        if (GlobalSummonCooldownTracker.get().remainingMs(player.getUUID(), globalCooldownMs) > 0L) {
            return SummonResult.GLOBAL_COOLDOWN;
        }

        // Airborne players can't summon — pet would either fall or path to an unreachable
        // target. Applies to all summon paths (walk and materialize).
        if (Config.REQUIRE_SPACE.get() && !isPlayerGrounded(player)) return SummonResult.PLAYER_AIRBORNE;

        ServerLevel playerLevel = (ServerLevel) player.level();
        PetSummonSavedData saved = PetSummonSavedData.get(playerLevel);
        ResourceKey<Level> playerDim = playerLevel.dimension();

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

    private static SummonResult discardAndMaterialize(ServerPlayer player, Entity old, Bond bond, ServerLevel playerLevel, PetSummonSavedData saved, SummonResult successResult) {
        UUID bondId = bond.bondId();
        CompoundTag freshNbt = old.saveWithoutId(new CompoundTag());
        old.discard();
        BondIndex.get().untrack(bondId, old);
        Bond refreshed = bond.withSnapshot(freshNbt, playerLevel.dimension(), player.position());
        SummonResult result = materializeFresh(player, refreshed, playerLevel, saved);
        return result == SummonResult.SUMMONED_FRESH ? successResult : result;
    }

    private static SummonResult materializeFresh(ServerPlayer player, Bond bond, ServerLevel targetLevel, PetSummonSavedData saved) {
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

        // Snapshots taken from a dead entity have health=0; restore unless death is permanent.
        if (!Config.DEATH_IS_PERMANENT.get() && entity instanceof LivingEntity living && living.getHealth() <= 0) {
            living.setHealth(living.getMaxHealth());
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
        Bond updated = bond.withRevision(newRevision).withLastSummonedAt(System.currentTimeMillis());
        player.setData(ModAttachments.BOND_ROSTER.get(), currentRoster.with(updated));

        GlobalSummonCooldownTracker.get().recordSummon(player.getUUID());

        return SummonResult.SUMMONED_FRESH;
    }

    /**
     * Plays the summon whistle (and optionally a poof) at the given location.
     * {@code withParticles} is true on materialize paths (entity appears at the player)
     * and false on the walk path (entity is already in the world, just being told to come).
     */
    private static void playSummonFx(ServerLevel level, double x, double y, double z, boolean withParticles) {
        if (withParticles) {
            level.sendParticles(ParticleTypes.POOF, x, y + 0.5D, z, 20, 0.3D, 0.3D, 0.3D, 0.05D);
        }
        Holder<SoundEvent> sound = SoundEvents.NOTE_BLOCK_FLUTE;
        level.playSound(null, x, y, z, sound, SoundSource.NEUTRAL, 0.7F, 1.5F);
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
        Bond updated = bond.withLastSummonedAt(System.currentTimeMillis());
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
     * Search a 5x5 (x/z) footprint around the player for a column whose ground supports
     * the entity's bounding box. Searches center-out (radius 0, then 1, then 2) and within
     * each column tries the player's level first, then up to 3 blocks below for an
     * overhang/step-down. Returns the spawn position (feet center, on top of the floor block)
     * or empty if nothing fits.
     */
    private static Optional<Vec3> findSpawnLocation(ServerLevel level, ServerPlayer player, EntityDimensions dims) {
        BlockPos pp = player.blockPosition();
        for (int r = 0; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    Optional<Vec3> spot = tryColumn(level, pp.offset(dx, 0, dz), dims);
                    if (spot.isPresent()) return spot;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Vec3> tryColumn(ServerLevel level, BlockPos start, EntityDimensions dims) {
        int minY = level.getMinBuildHeight();
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos top = start.below(dy);
            BlockPos floor = top.below();
            if (floor.getY() < minY) return Optional.empty();
            BlockState floorState = level.getBlockState(floor);
            if (!floorState.isFaceSturdy(level, floor, Direction.UP)) continue;
            // Floor is solid — check pocket above fits the entity.
            AABB box = dims.makeBoundingBox(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D);
            if (level.noCollision(box)) {
                return Optional.of(new Vec3(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D));
            }
            // Solid floor but pocket blocked — give up this column.
            return Optional.empty();
        }
        return Optional.empty();
    }

    private BondManager() {}
}
