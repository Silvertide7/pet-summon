package net.silvertide.petsummon.attachment;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public record BondRoster(Map<UUID, Bond> bonds, Optional<UUID> activePetId) {
    public static final BondRoster EMPTY = new BondRoster(Map.of(), Optional.empty());

    private static final Codec<BondRoster> NEW_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Bond.CODEC).fieldOf("bonds").forGetter(BondRoster::bonds),
            UUIDUtil.STRING_CODEC.optionalFieldOf("active").forGetter(BondRoster::activePetId)
    ).apply(instance, BondRoster::new));

    // Pre-active-pet shape: a flat map of bondId -> Bond. Kept so existing player files
    // upgrade cleanly. Loads as new with empty activePetId; next save uses new shape.
    private static final Codec<BondRoster> LEGACY_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Bond.CODEC)
            .xmap(map -> new BondRoster(map, Optional.empty()), BondRoster::bonds);

    public static final Codec<BondRoster> CODEC = Codec.either(NEW_CODEC, LEGACY_CODEC).xmap(
            either -> either.map(l -> l, r -> r),
            Either::left
    );

    public Optional<Bond> get(UUID bondId) {
        return Optional.ofNullable(bonds.get(bondId));
    }

    public BondRoster with(Bond bond) {
        Map<UUID, Bond> next = new LinkedHashMap<>(bonds);
        next.put(bond.bondId(), bond);
        return new BondRoster(next, activePetId);
    }

    public BondRoster without(UUID bondId) {
        if (!bonds.containsKey(bondId)) return this;
        Map<UUID, Bond> next = new LinkedHashMap<>(bonds);
        next.remove(bondId);
        // Clear active if the broken bond was the active one.
        Optional<UUID> nextActive = activePetId.filter(id -> !id.equals(bondId));
        return new BondRoster(next, nextActive);
    }

    /**
     * Returns a roster with the given bondId set as active. If the bondId isn't present
     * in this roster, returns this unchanged. Empty Optional clears the active pet.
     */
    public BondRoster withActive(Optional<UUID> bondId) {
        if (bondId.isPresent() && !bonds.containsKey(bondId.get())) return this;
        return new BondRoster(bonds, bondId);
    }

    public boolean isActive(UUID bondId) {
        return activePetId.map(id -> id.equals(bondId)).orElse(false);
    }

    public int size() {
        return bonds.size();
    }
}
