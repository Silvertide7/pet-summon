package net.silvertide.petsummon.server.events;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.silvertide.petsummon.PetSummon;
import net.silvertide.petsummon.attachment.Bond;
import net.silvertide.petsummon.attachment.BondRoster;
import net.silvertide.petsummon.registry.ModAttachments;
import net.silvertide.petsummon.network.ServerPacketHandler;
import net.silvertide.petsummon.server.BondIndex;
import net.silvertide.petsummon.server.OfflineSnapshot;
import net.silvertide.petsummon.server.PetSummonSavedData;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

@EventBusSubscriber(modid = PetSummon.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PlayerEvents {

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        if (roster.bonds().isEmpty()) return;

        PetSummonSavedData saved = PetSummonSavedData.get(level);
        BondRoster updated = roster;

        for (UUID bondId : new LinkedHashSet<>(roster.bonds().keySet())) {
            // Drain killed-while-offline first — the bond is gone before any snapshot matters.
            if (saved.wasKilledOffline(bondId)) {
                updated = updated.without(bondId);
                saved.clearBond(bondId);
                PetSummon.LOGGER.info("[petsummon] {} logged in to find bond {} died offline", player.getGameProfile().getName(), bondId);
                continue;
            }

            // Drain offline NBT snapshot.
            Optional<OfflineSnapshot> snap = saved.takeOfflineSnapshot(bondId);
            if (snap.isPresent()) {
                Optional<Bond> bond = updated.get(bondId);
                if (bond.isPresent()) {
                    OfflineSnapshot s = snap.get();
                    updated = updated.with(bond.get().withSnapshot(s.nbt(), s.dim(), s.pos()));
                }
            }
        }

        if (updated != roster) {
            player.setData(ModAttachments.BOND_ROSTER.get(), updated);
        }

        // Legacy migration: ensure the "bonds non-empty ⇒ active set" invariant holds for
        // players whose data predates auto-active-on-claim. Promote oldest-bonded.
        BondRoster current = player.getData(ModAttachments.BOND_ROSTER.get());
        if (!current.bonds().isEmpty() && current.activePetId().isEmpty()) {
            Optional<UUID> oldest = current.bonds().values().stream()
                    .min(Comparator.comparingLong(Bond::bondedAt))
                    .map(Bond::bondId);
            if (oldest.isPresent()) {
                player.setData(ModAttachments.BOND_ROSTER.get(), current.withActive(oldest));
            }
        }

        // Push initial roster snapshot so the keybind has data before the screen opens.
        ServerPacketHandler.sendRosterSync(player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        flushLoadedSnapshots(player);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Final flush so live entity state is captured into player attachments before save.
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            flushLoadedSnapshots(player);
        }
        BondIndex.get().clear();
    }

    /**
     * Snapshot every loaded bonded entity belonging to the player into their roster.
     * Used by logout and server-stop to capture state the player attachment would
     * otherwise miss (since the entity hasn't unloaded yet).
     */
    private static void flushLoadedSnapshots(ServerPlayer player) {
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        if (roster.bonds().isEmpty()) return;

        BondRoster updated = roster;
        for (UUID bondId : roster.bonds().keySet()) {
            Optional<Entity> entity = BondIndex.get().find(bondId);
            if (entity.isEmpty()) continue;

            Entity e = entity.get();
            Optional<Bond> bond = updated.get(bondId);
            if (bond.isEmpty()) continue;

            CompoundTag nbt = e.saveWithoutId(new CompoundTag());
            updated = updated.with(bond.get().withSnapshot(nbt, e.level().dimension(), e.position()));
        }

        if (updated != roster) {
            player.setData(ModAttachments.BOND_ROSTER.get(), updated);
        }
    }

    private PlayerEvents() {}
}
