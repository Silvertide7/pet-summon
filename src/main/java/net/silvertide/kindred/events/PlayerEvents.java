package net.silvertide.kindred.events;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.silvertide.kindred.config.Config;
import net.silvertide.kindred.network.packet.S2CCancelHold;
import net.silvertide.kindred.Kindred;
import net.silvertide.kindred.attachment.Bond;
import net.silvertide.kindred.attachment.BondRoster;
import net.silvertide.kindred.registry.ModAttachments;
import net.silvertide.kindred.network.ServerPacketHandler;
import net.silvertide.kindred.bond.BondIndex;
import net.silvertide.kindred.data.OfflineSnapshot;
import net.silvertide.kindred.data.KindredSavedData;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

@EventBusSubscriber(modid = Kindred.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PlayerEvents {

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        if (roster.bonds().isEmpty()) return;

        KindredSavedData saved = KindredSavedData.get(level);
        BondRoster updated = roster;

        for (UUID bondId : new LinkedHashSet<>(roster.bonds().keySet())) {
            // Drain killed-while-offline first — the bond is gone before any snapshot matters.
            if (saved.wasKilledOffline(bondId)) {
                updated = updated.without(bondId);
                saved.clearBond(bondId);
                Kindred.LOGGER.info("[kindred] {} logged in to find bond {} died offline", player.getGameProfile().getName(), bondId);
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

        // Push initial roster snapshot so the keybind has data before the screen opens.
        ServerPacketHandler.sendRosterSync(player);
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (!Config.CANCEL_HOLD_ON_DAMAGE.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PacketDistributor.sendToPlayer(player, new S2CCancelHold());
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
