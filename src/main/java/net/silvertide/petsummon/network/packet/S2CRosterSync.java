package net.silvertide.petsummon.network.packet;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.network.BondView;

import java.util.List;

/**
 * Full roster snapshot. {@code globalCooldownRemainingMs} is the player's roster-wide
 * summon cooldown remaining at send time; the client measures elapsed since receive.
 */
public record S2CRosterSync(List<BondView> bonds, long globalCooldownRemainingMs) implements CustomPacketPayload {
    public static final Type<S2CRosterSync> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PetSummon.MODID, "s2c_roster_sync"));

    public static final StreamCodec<ByteBuf, S2CRosterSync> STREAM_CODEC = StreamCodec.composite(
            BondView.STREAM_CODEC.apply(ByteBufCodecs.list()), S2CRosterSync::bonds,
            ByteBufCodecs.VAR_LONG, S2CRosterSync::globalCooldownRemainingMs,
            S2CRosterSync::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
