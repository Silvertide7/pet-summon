package net.silvertide.petsummon.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.client.network.ClientPacketHandler;
import net.silvertide.petsummon.network.packet.C2SBreakBond;
import net.silvertide.petsummon.network.packet.C2SClaimEntity;
import net.silvertide.petsummon.network.packet.C2SDismissBond;
import net.silvertide.petsummon.network.packet.C2SOpenRoster;
import net.silvertide.petsummon.network.packet.C2SSetActivePet;
import net.silvertide.petsummon.network.packet.C2SSummonBond;
import net.silvertide.petsummon.network.packet.C2SSummonByKeybind;
import net.silvertide.petsummon.network.packet.S2CRosterSync;

@EventBusSubscriber(modid = PetSummon.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class Networking {

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PetSummon.MODID).versioned("1");

        registrar.playToServer(C2SOpenRoster.TYPE, C2SOpenRoster.STREAM_CODEC, ServerPacketHandler::onOpenRoster);
        registrar.playToServer(C2SSummonByKeybind.TYPE, C2SSummonByKeybind.STREAM_CODEC, ServerPacketHandler::onSummonByKeybind);
        registrar.playToServer(C2SSummonBond.TYPE, C2SSummonBond.STREAM_CODEC, ServerPacketHandler::onSummonBond);
        registrar.playToServer(C2SBreakBond.TYPE, C2SBreakBond.STREAM_CODEC, ServerPacketHandler::onBreakBond);
        registrar.playToServer(C2SClaimEntity.TYPE, C2SClaimEntity.STREAM_CODEC, ServerPacketHandler::onClaimEntity);
        registrar.playToServer(C2SSetActivePet.TYPE, C2SSetActivePet.STREAM_CODEC, ServerPacketHandler::onSetActivePet);
        registrar.playToServer(C2SDismissBond.TYPE, C2SDismissBond.STREAM_CODEC, ServerPacketHandler::onDismissBond);

        registrar.playToClient(S2CRosterSync.TYPE, S2CRosterSync.STREAM_CODEC, ClientPacketHandler::onRosterSync);
    }

    private Networking() {}
}
