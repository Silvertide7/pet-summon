package net.silvertide.petsummon.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.network.BondView;

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
            PetSummon.LOGGER.warn("[petsummon] Failed to construct preview entity for {}: {}", view.entityType(), t.getMessage());
            return null;
        }
        if (!(raw instanceof LivingEntity living)) return null;
        try {
            raw.load(view.nbtSnapshot());
        } catch (Throwable t) {
            PetSummon.LOGGER.warn("[petsummon] Failed to load NBT for preview entity {}: {}", view.entityType(), t.getMessage());
            return null;
        }
        return living;
    }

    public static void clear() {
        cache.clear();
    }

    private PreviewEntityCache() {}
}
