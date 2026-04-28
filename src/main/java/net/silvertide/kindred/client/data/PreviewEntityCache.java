package net.silvertide.kindred.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.silvertide.kindred.Kindred;
import net.silvertide.kindred.network.BondView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lazy client-side cache of preview {@link LivingEntity} instances built from each
 * bond's snapshot NBT. The instances exist only for {@code InventoryScreen}'s render
 * API — they are never added to a level, never tick, and have no chunk presence.
 *
 * <p>Cache is cleared on screen close. Construction is on-demand: the first render
 * of a given bond builds and caches the entity; subsequent renders hit the cache.
 * The first render of a never-seen-this-session entity type pays a one-time
 * texture/model load (~50–100 ms) — that's a Minecraft-renderer cache miss, not
 * something we can avoid by caching entity instances.</p>
 */
public final class PreviewEntityCache {
    private static final Map<UUID, LivingEntity> cache = new HashMap<>();

    public static LivingEntity getOrBuild(BondView view) {
        LivingEntity cached = cache.get(view.bondId());
        if (cached != null) return cached;
        LivingEntity built = build(view);
        if (built != null) cache.put(view.bondId(), built);
        return built;
    }

    private static LivingEntity build(BondView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(view.entityType());
        if (type == null) return null;
        Entity raw;
        try {
            raw = type.create(mc.level);
        } catch (Throwable t) {
            Kindred.LOGGER.warn("[kindred] Failed to construct preview entity for {}: {}", view.entityType(), t.getMessage());
            return null;
        }
        if (!(raw instanceof LivingEntity living)) return null;
        try {
            raw.load(view.nbtSnapshot());
        } catch (Throwable t) {
            Kindred.LOGGER.warn("[kindred] Failed to load NBT for preview entity {}: {}", view.entityType(), t.getMessage());
            return null;
        }
        freshenForPreview(living, view.nbtSnapshot());
        return living;
    }

    /**
     * Strip transient damage/death state from the snapshot so the preview always
     * renders alive and unhurt — no red hurt-flash, no death-collapse pose, no
     * fire overlay — regardless of how the pet was when it was last snapshotted.
     * Also hides the floating nametag so it doesn't overlap the preview render;
     * the roster row's name still uses {@code bond.displayName()}.
     */
    private static void freshenForPreview(LivingEntity living, CompoundTag nbt) {
        living.setHealth(living.getMaxHealth());
        living.hurtTime = 0;
        living.deathTime = 0;
        living.clearFire();
        living.removeAllEffects();
        living.setAirSupply(living.getMaxAirSupply());
        living.fallDistance = 0F;
        living.setTicksFrozen(0);
        living.setCustomNameVisible(false);

        // Saddle isn't tracked in standard equipment slots — it lives in a separate
        // inventory slot with the "show saddle" state driven by a SynchedEntityData
        // flag bit. The flag is normally set by AbstractHorse.updateContainerEquipment,
        // which no-ops on the client side. The preview entity never gets the synced
        // value, so we read the saddle presence from the snapshot NBT and push the
        // bit ourselves. Bit 4 of DATA_ID_FLAGS is FLAG_SADDLE.
        if (living instanceof AbstractHorse horse) {
            boolean saddled = nbt.contains("SaddleItem", Tag.TAG_COMPOUND);
            horse.setFlag(4, saddled);
        }
    }

    public static void clear() {
        cache.clear();
    }

    private PreviewEntityCache() {}
}
