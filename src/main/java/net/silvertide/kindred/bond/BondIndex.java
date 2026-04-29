package net.silvertide.kindred.bond;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live index of bonded entities currently loaded in the world, keyed by bondId.
 * Maintained incrementally via EntityJoinLevelEvent / EntityLeaveLevelEvent.
 * Used by BondService to avoid scanning every entity in every dimension on summon.
 */
public final class BondIndex {
    private static final BondIndex INSTANCE = new BondIndex();

    public static BondIndex get() {
        return INSTANCE;
    }

    private final Map<UUID, Entity> entitiesByBondId = new ConcurrentHashMap<>();

    public void track(UUID bondId, Entity entity) {
        entitiesByBondId.put(bondId, entity);
    }

    public void untrack(UUID bondId) {
        entitiesByBondId.remove(bondId);
    }

    public void untrack(UUID bondId, Entity expected) {
        entitiesByBondId.remove(bondId, expected);
    }

    public Optional<Entity> find(UUID bondId) {
        Entity e = entitiesByBondId.get(bondId);
        if (e == null || e.isRemoved()) return Optional.empty();
        return Optional.of(e);
    }

    public void clear() {
        entitiesByBondId.clear();
    }

    private BondIndex() {}
}
