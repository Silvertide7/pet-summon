package net.silvertide.kindred.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.silvertide.kindred.Kindred;

public final class ModTags {
    /** Entities in this tag refuse to be bonded. Reads as a property of the entity:
     *  "this thing can't be bonded" — same shape as vanilla {@code #fire_immune}. */
    public static final TagKey<EntityType<?>> CANT_BOND = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(Kindred.MODID, "cant_bond")
    );

    /** Dimension types in this tag block summoning while the player is in them.
     *  Tags dimension types (overworld, nether, custom mod dims) — most servers
     *  want to ban by type, not by named dimension instance. */
    public static final TagKey<DimensionType> NO_SUMMON_DIMENSIONS = TagKey.create(
            Registries.DIMENSION_TYPE,
            ResourceLocation.fromNamespaceAndPath(Kindred.MODID, "no_summon_dimensions")
    );

    /** Biomes in this tag block summoning while the player stands in them.
     *  Targets the destination biome — i.e. where the pet would materialize. */
    public static final TagKey<Biome> NO_SUMMON_BIOMES = TagKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(Kindred.MODID, "no_summon_biomes")
    );

    private ModTags() {}
}
