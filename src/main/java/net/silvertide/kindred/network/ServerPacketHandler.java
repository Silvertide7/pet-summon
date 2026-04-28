package net.silvertide.kindred.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.silvertide.kindred.attachment.Bond;
import net.silvertide.kindred.attachment.BondRoster;
import net.silvertide.kindred.config.Config;
import net.silvertide.kindred.server.BondIndex;
import net.silvertide.kindred.network.packet.C2SBreakBond;
import net.silvertide.kindred.network.packet.C2SCheckBindCandidate;
import net.silvertide.kindred.network.packet.C2SClaimEntity;
import net.silvertide.kindred.network.packet.C2SDismissBond;
import net.silvertide.kindred.network.packet.C2SOpenRoster;
import net.silvertide.kindred.network.packet.C2SRenameBond;
import net.silvertide.kindred.network.packet.C2SSetActivePet;
import net.silvertide.kindred.network.packet.C2SSummonBond;
import net.silvertide.kindred.network.packet.C2SSummonByKeybind;
import net.silvertide.kindred.network.packet.S2CBindCandidateResult;
import net.silvertide.kindred.network.packet.S2CRosterSync;
import net.silvertide.kindred.registry.ModAttachments;
import net.silvertide.kindred.server.BondManager;
import net.silvertide.kindred.server.GlobalSummonCooldownTracker;

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

    public static void onCheckBindCandidate(C2SCheckBindCandidate payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ServerLevel level = (ServerLevel) player.level();
            Entity target = level.getEntity(payload.entityUUID());
            // Entity gone or out of reach — silent rejection (no message). The screen
            // falls back to the generic "look at a tamed pet" hint in that case.
            if (target == null || target.distanceToSqr(player) > MAX_CLAIM_DISTANCE_SQ) {
                PacketDistributor.sendToPlayer(player, new S2CBindCandidateResult(
                        payload.entityUUID(), false, Optional.empty()));
                return;
            }
            BondManager.ClaimResult result = BondManager.checkClaimEligibility(player, target);
            boolean canBind = result == BondManager.ClaimResult.CLAIMED;
            Optional<String> denyKey = canBind ? Optional.empty() : Optional.of(denyKeyFor(result));
            PacketDistributor.sendToPlayer(player, new S2CBindCandidateResult(
                    payload.entityUUID(), canBind, denyKey));
        });
    }

    private static String denyKeyFor(BondManager.ClaimResult result) {
        return switch (result) {
            case NOT_OWNABLE -> "kindred.bind.deny.not_ownable";
            case NOT_OWNED_BY_PLAYER -> "kindred.bind.deny.not_owned";
            case BLOCKLISTED -> "kindred.bind.deny.blocklisted";
            case REQUIRES_SADDLEABLE -> "kindred.bind.deny.requires_saddleable";
            case AT_CAPACITY -> "kindred.bind.deny.at_capacity";
            case ALREADY_BONDED -> "kindred.bind.deny.already_bonded";
            default -> "kindred.bind.deny.generic";
        };
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

    private static final int MAX_NAME_LEN = 32;

    public static void onRenameBond(C2SRenameBond payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
            Optional<Bond> bond = roster.get(payload.bondId());
            if (bond.isEmpty()) return;
            Optional<String> sanitized = payload.newName()
                    .map(ServerPacketHandler::sanitizeName)
                    .filter(s -> !s.isEmpty());
            Bond updated = bond.get().withDisplayName(sanitized);
            player.setData(ModAttachments.BOND_ROSTER.get(), roster.with(updated));
            // Mirror the rename onto the live entity so the in-world nametag updates
            // immediately. Offline pets pick this up on next materialize from displayName.
            BondIndex.get().find(payload.bondId())
                    .ifPresent(e -> BondManager.applyDisplayName(e, sanitized));
            sendRosterSync(player);
        });
    }

    private static String sanitizeName(String raw) {
        if (raw == null) return "";
        String s = raw.replace("§", "");        // strip Minecraft formatting codes
        s = s.replaceAll("\\p{Cntrl}", "");          // strip control chars (newlines, tabs, etc.)
        s = s.trim();
        if (s.length() > MAX_NAME_LEN) s = s.substring(0, MAX_NAME_LEN);
        return s;
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
        long revivalCooldownMs = Config.revivalCooldownMs();
        List<BondView> views = roster.bonds().values().stream()
                .sorted(Comparator.comparingLong(Bond::bondedAt))
                .map(b -> {
                    long remaining = Math.max(0L, cooldownMs - (now - b.lastSummonedAt()));
                    long revivalRemaining = 0L;
                    if (revivalCooldownMs > 0L && b.diedAt().isPresent()) {
                        revivalRemaining = Math.max(0L, revivalCooldownMs - (now - b.diedAt().get()));
                    }
                    Optional<Entity> live = BondIndex.get().find(b.bondId());
                    boolean loaded = live.isPresent();
                    // Capture live NBT for loaded pets so saddle/armor/equipment changes
                    // made in-world flow into the preview without waiting for the pet
                    // to leave its chunk (which is when the cached snapshot refreshes).
                    CompoundTag nbt = loaded
                            ? live.get().saveWithoutId(new CompoundTag())
                            : b.nbtSnapshot();
                    return BondView.from(b, roster.isActive(b.bondId()), loaded, remaining, revivalRemaining, nbt);
                })
                .toList();
        long globalRemaining = GlobalSummonCooldownTracker.get()
                .remainingMs(player.getUUID(), Config.summonGlobalCooldownMs());
        PacketDistributor.sendToPlayer(player, new S2CRosterSync(views, globalRemaining));
    }

    private static boolean isSummonSuccess(BondManager.SummonResult result) {
        return result == BondManager.SummonResult.WALKING
                || result == BondManager.SummonResult.TELEPORTED_NEAR
                || result == BondManager.SummonResult.SUMMONED_FRESH;
    }

    private ServerPacketHandler() {}
}
