package net.silvertide.petsummon.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.client.input.Keybinds;

@EventBusSubscriber(modid = PetSummon.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(Keybinds.SUMMON_ACTIVE_PET);
        event.register(Keybinds.OPEN_ROSTER);
    }

    private ClientSetup() {}
}
