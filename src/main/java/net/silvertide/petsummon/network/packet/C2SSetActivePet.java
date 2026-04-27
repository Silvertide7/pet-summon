package net.silvertide.petsummon.network.packet;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.silvertide.petsummon.PetSummon;

import java.util.Optional;
import java.util.UUID;

/**
 * Empty bondId clears the active pet. Present sets the active pet to that bondId
 * (server validates the bondId exists in the player's roster).
 */
public record C2SSetActivePet(Optional<UUID> bondId) implements CustomPacketPayload {
    public static final Type<C2SSetActivePet> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PetSummon.MODID, "c2s_set_active_pet"));

    public static final StreamCodec<ByteBuf, C2SSetActivePet> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), C2SSetActivePet::bondId,
            C2SSetActivePet::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
