package net.silvertide.petsummon.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;
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
        NO_SPACE,
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

        player.setData(ModAttachments.BOND_ROSTER.get(), roster.with(bond));
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

        if (Config.REQUIRE_SPACE.get() && !hasSpaceAround(player)) return SummonResult.NO_SPACE;

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
                    writeSummonTimestamp(player, bond, roster);
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

        entity.setPos(player.getX(), player.getY(), player.getZ());

        int newRevision = saved.incrementRevision(bond.bondId());
        entity.setData(ModAttachments.BONDED.get(), new Bonded(bond.bondId(), player.getUUID(), newRevision));

        // addFreshEntity fires EntityJoinLevelEvent, which our handler responds to by tracking
        // in BondIndex. No explicit track needed here.
        if (!targetLevel.addFreshEntity(entity)) return SummonResult.SPAWN_FAILED;

        // Re-read roster: the cross-dim path discards the old entity above, which fires the
        // leave event synchronously and writes a snapshot into the player's roster. Trusting
        // a roster captured before that point would silently drop the leave-event's write.
        BondRoster currentRoster = player.getData(ModAttachments.BOND_ROSTER.get());
        Bond updated = bond.withRevision(newRevision).withLastSummonedAt(System.currentTimeMillis());
        player.setData(ModAttachments.BOND_ROSTER.get(), currentRoster.with(updated));

        return SummonResult.SUMMONED_FRESH;
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

    // TODO (FEATURES.md tier 2 — smarter spawn placement):
    //   - Search a 5x5 (x/z) footprint for a valid 3x3x3 pocket; pick the closest free spot
    //     rather than refusing if the player's exact tile is blocked.
    //   - Always spawn on the ground (top of a solid block in the search area), not at the
    //     player's feet level if they're mid-air.
    //   - Refuse summon when the player is >1 block above ground; add a PLAYER_AIRBORNE
    //     SummonResult and return it before this check is reached.
    private static boolean hasSpaceAround(ServerPlayer player) {
        BlockPos origin = player.blockPosition();
        Level level = player.level();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    var state = level.getBlockState(pos);
                    if (!state.getCollisionShape(level, pos).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    private BondManager() {}
}
