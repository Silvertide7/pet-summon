package net.silvertide.petsummon.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.silvertide.petsummon.attachment.Bond;

import java.util.Optional;
import java.util.UUID;

/**
 * Compact server-to-client representation of a bond. Excludes the full NBT snapshot
 * (which the client doesn't need for display) and other server-only fields.
 */
public record BondView(
        UUID bondId,
        ResourceLocation entityType,
        Optional<String> displayName,
        ResourceLocation lastSeenDim,
        Vec3 lastSeenPos,
        boolean isActive
) {
    public static final StreamCodec<ByteBuf, Vec3> VEC3_STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x,
            ByteBufCodecs.DOUBLE, Vec3::y,
            ByteBufCodecs.DOUBLE, Vec3::z,
            Vec3::new
    );

    public static final StreamCodec<ByteBuf, BondView> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BondView::bondId,
            ResourceLocation.STREAM_CODEC, BondView::entityType,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), BondView::displayName,
            ResourceLocation.STREAM_CODEC, BondView::lastSeenDim,
            VEC3_STREAM_CODEC, BondView::lastSeenPos,
            ByteBufCodecs.BOOL, BondView::isActive,
            BondView::new
    );

    public static BondView from(Bond bond, boolean isActive) {
        return new BondView(
                bond.bondId(),
                bond.entityType(),
                bond.displayName(),
                bond.lastSeenDim().location(),
                bond.lastSeenPos(),
                isActive
        );
    }
}
