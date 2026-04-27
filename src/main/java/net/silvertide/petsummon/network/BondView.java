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
 * Compact server-to-client representation of a bond.
 *
 * <p>{@code entityUUID} is extracted from the snapshot NBT so the client can find the
 * loaded entity (proximity check for hold-to-dismiss).</p>
 *
 * <p>{@code cooldownRemainingMs} is computed at send time. The client measures elapsed
 * time from the moment it received the roster sync, so this stays correct under any
 * server↔client clock skew — the only error is one-way network latency.</p>
 */
public record BondView(
        UUID bondId,
        UUID entityUUID,
        ResourceLocation entityType,
        Optional<String> displayName,
        ResourceLocation lastSeenDim,
        Vec3 lastSeenPos,
        boolean isActive,
        long cooldownRemainingMs
) {
    public static final StreamCodec<ByteBuf, Vec3> VEC3_STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x,
            ByteBufCodecs.DOUBLE, Vec3::y,
            ByteBufCodecs.DOUBLE, Vec3::z,
            Vec3::new
    );

    // 8 fields exceeds StreamCodec.composite's max arity, so write it by hand.
    public static final StreamCodec<ByteBuf, BondView> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BondView decode(ByteBuf buf) {
            UUID bondId = UUIDUtil.STREAM_CODEC.decode(buf);
            UUID entityUUID = UUIDUtil.STREAM_CODEC.decode(buf);
            ResourceLocation entityType = ResourceLocation.STREAM_CODEC.decode(buf);
            Optional<String> displayName = ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8).decode(buf);
            ResourceLocation lastSeenDim = ResourceLocation.STREAM_CODEC.decode(buf);
            Vec3 lastSeenPos = VEC3_STREAM_CODEC.decode(buf);
            boolean isActive = ByteBufCodecs.BOOL.decode(buf);
            long cooldownRemainingMs = ByteBufCodecs.VAR_LONG.decode(buf);
            return new BondView(bondId, entityUUID, entityType, displayName, lastSeenDim, lastSeenPos, isActive, cooldownRemainingMs);
        }

        @Override
        public void encode(ByteBuf buf, BondView v) {
            UUIDUtil.STREAM_CODEC.encode(buf, v.bondId());
            UUIDUtil.STREAM_CODEC.encode(buf, v.entityUUID());
            ResourceLocation.STREAM_CODEC.encode(buf, v.entityType());
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8).encode(buf, v.displayName());
            ResourceLocation.STREAM_CODEC.encode(buf, v.lastSeenDim());
            VEC3_STREAM_CODEC.encode(buf, v.lastSeenPos());
            ByteBufCodecs.BOOL.encode(buf, v.isActive());
            ByteBufCodecs.VAR_LONG.encode(buf, v.cooldownRemainingMs());
        }
    };

    private static final UUID NO_UUID = new UUID(0L, 0L);

    public static BondView from(Bond bond, boolean isActive, long cooldownRemainingMs) {
        UUID entityUUID = bond.nbtSnapshot().hasUUID("UUID")
                ? bond.nbtSnapshot().getUUID("UUID")
                : NO_UUID;
        return new BondView(
                bond.bondId(),
                entityUUID,
                bond.entityType(),
                bond.displayName(),
                bond.lastSeenDim().location(),
                bond.lastSeenPos(),
                isActive,
                cooldownRemainingMs
        );
    }
}
