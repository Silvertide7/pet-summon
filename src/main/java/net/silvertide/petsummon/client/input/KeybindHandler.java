package net.silvertide.petsummon.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.client.data.ClientRosterData;
import net.silvertide.petsummon.client.data.HoldActionState;
import net.silvertide.petsummon.client.screen.RosterScreen;
import net.silvertide.petsummon.config.Config;
import net.silvertide.petsummon.network.BondView;
import net.silvertide.petsummon.network.packet.C2SDismissBond;
import net.silvertide.petsummon.network.packet.C2SSummonByKeybind;

import java.util.Optional;
import java.util.UUID;

@EventBusSubscriber(modid = PetSummon.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class KeybindHandler {

    /**
     * Once a keybind action fires, require the user to release the key before another
     * action can fire. Prevents the "dismiss then immediately summon while still
     * holding" rebound and any other scenario where {@code consumeClick} repeats.
     */
    private static boolean requireRelease = false;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // Open roster: simple tap.
        while (Keybinds.OPEN_ROSTER.consumeClick()) {
            Minecraft.getInstance().setScreen(new RosterScreen());
        }

        // Clear the release-gate as soon as the key actually goes up.
        if (requireRelease && !Keybinds.SUMMON_ACTIVE_PET.isDown()) {
            requireRelease = false;
        }

        // Press detection: arm hold-to-dismiss if any bonded pet is nearby, otherwise
        // arm hold-to-summon (after pre-checking cooldown / empty roster).
        while (Keybinds.SUMMON_ACTIVE_PET.consumeClick()) {
            if (requireRelease) continue;
            if (HoldActionState.isActive()) continue;

            BondView dismissTarget = findNearbyDismissTarget();
            if (dismissTarget != null) {
                HoldActionState.startDismiss(dismissTarget.bondId(),
                        Config.HOLD_TO_DISMISS_MS.get());
                continue;
            }

            // Summon path — short-circuit if no bonds, no active, or on cooldown.
            // Server is still authoritative; this avoids making the player wait a full
            // hold for a guaranteed rejection.
            if (ClientRosterData.bonds().isEmpty()) {
                showActionBar("petsummon.summon.no_bonds");
                continue;
            }
            Optional<BondView> target = ClientRosterData.findActive();
            if (target.isEmpty()) {
                showActionBar("petsummon.summon.no_active");
                continue;
            }
            if (ClientRosterData.isOnCooldown(target.get())) {
                showActionBar("petsummon.summon.on_cooldown");
                continue;
            }
            HoldActionState.startSummon(Config.HOLD_TO_SUMMON_MS.get());
        }

        // Drive the hold timer forward.
        if (HoldActionState.isActive()) {
            if (!Keybinds.SUMMON_ACTIVE_PET.isDown()) {
                HoldActionState.cancel();
            } else if (HoldActionState.isComplete()) {
                HoldActionState.Mode mode = HoldActionState.mode();
                UUID bondId = HoldActionState.bondId();
                HoldActionState.cancel();
                if (mode == HoldActionState.Mode.DISMISS && bondId != null) {
                    PacketDistributor.sendToServer(new C2SDismissBond(bondId));
                } else if (mode == HoldActionState.Mode.SUMMON) {
                    PacketDistributor.sendToServer(new C2SSummonByKeybind());
                }
                requireRelease = true;
            }
        }
    }

    /**
     * Returns the active bond's BondView if its loaded entity is within
     * {@link HoldActionState#DISMISS_RADIUS} blocks of the local player, otherwise null.
     *
     * Strictly active-only: the keybind always targets the active pet. If the active
     * is nearby, holding dismisses it; if the active is far/unloaded, holding summons
     * it. Other bonded pets in the area do not influence the keybind — use the
     * roster screen's Dismiss button to recall a non-active pet.
     */
    private static BondView findNearbyDismissTarget() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return null;

        Optional<BondView> active = ClientRosterData.findActive();
        if (active.isEmpty()) return null;
        Entity activeEntity = findLoadedEntity(level, active.get().entityUUID());
        if (activeEntity == null) return null;
        if (activeEntity.distanceToSqr(p) > HoldActionState.DISMISS_RADIUS_SQ) return null;
        return active.get();
    }

    private static Entity findLoadedEntity(ClientLevel level, UUID uuid) {
        for (Entity e : level.entitiesForRendering()) {
            if (!e.isRemoved() && uuid.equals(e.getUUID())) return e;
        }
        return null;
    }

    private static void showActionBar(String langKey) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.translatable(langKey), true);
    }

    private KeybindHandler() {
        PetSummon.LOGGER.trace("KeybindHandler init");
    }
}
