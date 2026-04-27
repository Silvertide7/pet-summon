package net.silvertide.petsummon.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.client.screen.HoldActionOverlay;

@EventBusSubscriber(modid = PetSummon.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientGuiLayers {

    @SubscribeEvent
    public static void onRegister(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(PetSummon.MODID, "hold_action"),
                HoldActionOverlay::render
        );
    }

    private ClientGuiLayers() {}
}
