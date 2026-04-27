package net.silvertide.petsummon.client.input;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.client.screen.RosterScreen;
import net.silvertide.petsummon.network.packet.C2SSummonByKeybind;

@EventBusSubscriber(modid = PetSummon.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class KeybindHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (Keybinds.SUMMON_ACTIVE_PET.consumeClick()) {
            PacketDistributor.sendToServer(new C2SSummonByKeybind());
        }
        while (Keybinds.OPEN_ROSTER.consumeClick()) {
            Minecraft.getInstance().setScreen(new RosterScreen());
        }
    }

    private KeybindHandler() {}
}
