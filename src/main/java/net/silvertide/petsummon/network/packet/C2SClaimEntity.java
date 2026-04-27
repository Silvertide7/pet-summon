package net.silvertide.petsummon.network.packet;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.silvertide.petsummon.PetSummon;

import java.util.UUID;

public record C2SClaimEntity(UUID entityUUID) implements CustomPacketPayload {
    public static final Type<C2SClaimEntity> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PetSummon.MODID, "c2s_claim_entity"));

    public static final StreamCodec<ByteBuf, C2SClaimEntity> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, C2SClaimEntity::entityUUID,
            C2SClaimEntity::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
