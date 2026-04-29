package net.silvertide.kindred.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public record OfflineSnapshot(CompoundTag nbt, ResourceKey<Level> dim, Vec3 pos) {
    public static final Codec<OfflineSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CompoundTag.CODEC.fieldOf("nbt").forGetter(OfflineSnapshot::nbt),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dim").forGetter(OfflineSnapshot::dim),
            Vec3.CODEC.fieldOf("pos").forGetter(OfflineSnapshot::pos)
    ).apply(instance, OfflineSnapshot::new));
}
