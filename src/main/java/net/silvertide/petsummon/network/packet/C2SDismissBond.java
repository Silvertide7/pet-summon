package net.silvertide.petsummon.network.packet;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.silvertide.petsummon.PetSummon;

import java.util.UUID;

public record C2SDismissBond(UUID bondId) implements CustomPacketPayload {
    public static final Type<C2SDismissBond> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PetSummon.MODID, "c2s_dismiss_bond"));

    public static final StreamCodec<ByteBuf, C2SDismissBond> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, C2SDismissBond::bondId,
            C2SDismissBond::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
