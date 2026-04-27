package net.silvertide.petsummon.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.silvertide.petsummon.attachment.Bond;
import net.silvertide.petsummon.attachment.BondRoster;
import net.silvertide.petsummon.config.Config;
import net.silvertide.petsummon.network.packet.C2SBreakBond;
import net.silvertide.petsummon.network.packet.C2SClaimEntity;
import net.silvertide.petsummon.network.packet.C2SDismissBond;
import net.silvertide.petsummon.network.packet.C2SOpenRoster;
import net.silvertide.petsummon.network.packet.C2SSetActivePet;
import net.silvertide.petsummon.network.packet.C2SSummonBond;
import net.silvertide.petsummon.network.packet.C2SSummonByKeybind;
import net.silvertide.petsummon.network.packet.S2CRosterSync;
import net.silvertide.petsummon.registry.ModAttachments;
import net.silvertide.petsummon.server.BondManager;
import net.silvertide.petsummon.server.GlobalSummonCooldownTracker;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ServerPacketHandler {

    /** Server-side guard against spoofed claim packets. Client raycast caps at 8. */
    private static final double MAX_CLAIM_DISTANCE_SQ = 12.0D * 12.0D;

    public static void onOpenRoster(C2SOpenRoster payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            sendRosterSync(player);
        });
    }

    public static void onSummonByKeybind(C2SSummonByKeybind payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
            if (roster.bonds().isEmpty()) {
                player.sendSystemMessage(Component.literal("No bonds to summon."));
                return;
            }
            // The keybind always targets the active pet. By invariant (set on claim,
            // restored on break, migrated on login) this is non-empty whenever bonds is.
            Optional<UUID> activeId = roster.activePetId();
            if (activeId.isEmpty()) {
                player.sendSystemMessage(Component.literal("No active pet set."));
                return;
            }
            BondManager.SummonResult result = BondManager.summon(player, activeId.get());
            player.sendSystemMessage(Component.literal("Summon: " + result.name()));
            if (isSummonSuccess(result)) sendRosterSync(player);
        });
    }

    public static void onSummonBond(C2SSummonBond payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BondManager.SummonResult result = BondManager.summon(player, payload.bondId());
            player.sendSystemMessage(Component.literal("Summon: " + result.name()));
            if (isSummonSuccess(result)) sendRosterSync(player);
        });
    }

    public static void onBreakBond(C2SBreakBond payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BondManager.BreakResult result = BondManager.breakBond(player, payload.bondId());
            player.sendSystemMessage(Component.literal("Break: " + result.name()));
            sendRosterSync(player);
        });
    }

    public static void onDismissBond(C2SDismissBond payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BondManager.DismissResult result = BondManager.dismiss(player, payload.bondId());
            player.sendSystemMessage(Component.literal("Dismiss: " + result.name()));
            if (result == BondManager.DismissResult.DISMISSED) sendRosterSync(player);
        });
    }

    public static void onClaimEntity(C2SClaimEntity payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ServerLevel level = (ServerLevel) player.level();
            Entity target = level.getEntity(payload.entityUUID());
            if (target == null) {
                player.sendSystemMessage(Component.literal("Bind failed: entity not found."));
                return;
            }
            if (target.distanceToSqr(player) > MAX_CLAIM_DISTANCE_SQ) {
                player.sendSystemMessage(Component.literal("Bind failed: too far."));
                return;
            }
            BondManager.ClaimResult result = BondManager.tryClaim(player, target);
            if (result == BondManager.ClaimResult.CLAIMED) {
                String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
                player.sendSystemMessage(Component.literal("Claimed " + typeId + "."));
                sendRosterSync(player);
            } else {
                player.sendSystemMessage(Component.literal("Bind failed: " + result.name()));
            }
        });
    }

    public static void onSetActivePet(C2SSetActivePet payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
            BondRoster updated = roster.withActive(payload.bondId());
            if (updated != roster) {
                player.setData(ModAttachments.BOND_ROSTER.get(), updated);
            }
            sendRosterSync(player);
        });
    }

    public static void sendRosterSync(ServerPlayer player) {
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        long now = System.currentTimeMillis();
        long cooldownMs = Config.SUMMON_COOLDOWN_TICKS.get() * 50L;
        List<BondView> views = roster.bonds().values().stream()
                .sorted(Comparator.comparingLong(Bond::bondedAt))
                .map(b -> {
                    long remaining = Math.max(0L, cooldownMs - (now - b.lastSummonedAt()));
                    return BondView.from(b, roster.isActive(b.bondId()), remaining);
                })
                .toList();
        long globalRemaining = GlobalSummonCooldownTracker.get()
                .remainingMs(player.getUUID(), Config.SUMMON_GLOBAL_COOLDOWN_MS.get());
        PacketDistributor.sendToPlayer(player, new S2CRosterSync(views, globalRemaining));
    }

    private static boolean isSummonSuccess(BondManager.SummonResult result) {
        return result == BondManager.SummonResult.WALKING
                || result == BondManager.SummonResult.TELEPORTED_NEAR
                || result == BondManager.SummonResult.SUMMONED_FRESH;
    }

    private ServerPacketHandler() {}
}
