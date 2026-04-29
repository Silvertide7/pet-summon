package net.silvertide.kindred.network.packet;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.silvertide.kindred.Kindred;
import net.silvertide.kindred.network.BondView;

import java.util.List;

/**
 * Full roster snapshot.
 *
 * @param globalCooldownRemainingMs player's roster-wide summon cooldown remaining at
 *                                  send time; the client measures elapsed since receive.
 * @param effectiveMaxBonds         player's current bond cap. Equal to {@code maxBonds}
 *                                  when PMMO compat is off; PMMO-adjusted otherwise.
 *                                  Drives the title-bar X/Y display and the at-capacity
 *                                  client-side gate.
 */
public record S2CRosterSync(List<BondView> bonds, long globalCooldownRemainingMs, int effectiveMaxBonds) implements CustomPacketPayload {
    public static final Type<S2CRosterSync> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Kindred.MODID, "s2c_roster_sync"));

    public static final StreamCodec<ByteBuf, S2CRosterSync> STREAM_CODEC = StreamCodec.composite(
            BondView.STREAM_CODEC.apply(ByteBufCodecs.list()), S2CRosterSync::bonds,
            ByteBufCodecs.VAR_LONG, S2CRosterSync::globalCooldownRemainingMs,
            ByteBufCodecs.VAR_INT, S2CRosterSync::effectiveMaxBonds,
            S2CRosterSync::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
