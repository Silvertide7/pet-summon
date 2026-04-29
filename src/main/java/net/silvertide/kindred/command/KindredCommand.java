package net.silvertide.kindred.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.silvertide.kindred.Kindred;
import net.silvertide.kindred.attachment.Bond;
import net.silvertide.kindred.attachment.BondRoster;
import net.silvertide.kindred.bond.BondService;
import net.silvertide.kindred.registry.ModAttachments;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Debug commands for exercising the bond system without a GUI.
 *
 *   /kindred claim                  — claim the entity at your crosshair (within 10 blocks)
 *   /kindred list                   — list your bonds with stable indices
 *   /kindred summon  &lt;index&gt;       — summon the bond at the given index
 *   /kindred dismiss &lt;index&gt;       — recall a loaded bond back to storage
 *   /kindred break   &lt;index&gt;       — break the bond at the given index
 *   /kindred active  &lt;index|none&gt;  — set or clear the active pet
 *
 * Indices are derived from your roster sorted by bondedAt (oldest first).
 */
@EventBusSubscriber(modid = Kindred.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class KindredCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("kindred")
                        .requires(src -> src.hasPermission(0))
                        .then(Commands.literal("claim").executes(KindredCommand::runClaim))
                        .then(Commands.literal("list").executes(KindredCommand::runList))
                        .then(Commands.literal("summon")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(KindredCommand::runSummon)))
                        .then(Commands.literal("break")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(KindredCommand::runBreak)))
                        .then(Commands.literal("dismiss")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(KindredCommand::runDismiss)))
                        .then(Commands.literal("active")
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(KindredCommand::runActive)))
        );
    }

    private static int runClaim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Entity target = raycastEntity(player, 10.0D);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("No entity in your line of sight."));
            return 0;
        }
        BondService.ClaimResult result = BondService.tryClaim(player, target);
        ctx.getSource().sendSuccess(() -> Component.literal("Claim " + describeEntityType(target) + ": " + result.name()), false);
        return result == BondService.ClaimResult.CLAIMED ? 1 : 0;
    }

    private static int runList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        List<Bond> bonds = sortedBonds(roster);

        if (bonds.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Bonds: (none)"), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Bonds (" + bonds.size() + "):"), false);
        for (int i = 0; i < bonds.size(); i++) {
            Bond b = bonds.get(i);
            int idx = i;
            String name = b.displayName().orElse("(no name)");
            String dim = b.lastSeenDim().location().toString();
            String pos = String.format("%.0f,%.0f,%.0f", b.lastSeenPos().x, b.lastSeenPos().y, b.lastSeenPos().z);
            String activeMarker = roster.isActive(b.bondId()) ? " [ACTIVE]" : "";
            String line = String.format("[%d] %s %s \"%s\" rev=%d last=%s @ %s%s",
                    idx, shortId(b.bondId()), b.entityType(), name, b.revision(), dim, pos, activeMarker);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return bonds.size();
    }

    private static int runSummon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int index = IntegerArgumentType.getInteger(ctx, "index");
        Bond bond = bondAt(player, index);
        if (bond == null) {
            ctx.getSource().sendFailure(Component.literal("No bond at index " + index + "."));
            return 0;
        }
        BondService.SummonResult result = BondService.summon(player, bond.bondId());
        ctx.getSource().sendSuccess(() -> Component.literal("Summon [" + index + "] " + shortId(bond.bondId()) + ": " + result.name()), false);
        return result == BondService.SummonResult.WALKING
                || result == BondService.SummonResult.TELEPORTED_NEAR
                || result == BondService.SummonResult.SUMMONED_FRESH ? 1 : 0;
    }

    private static int runBreak(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int index = IntegerArgumentType.getInteger(ctx, "index");
        Bond bond = bondAt(player, index);
        if (bond == null) {
            ctx.getSource().sendFailure(Component.literal("No bond at index " + index + "."));
            return 0;
        }
        BondService.BreakResult result = BondService.breakBond(player, bond.bondId());
        ctx.getSource().sendSuccess(() -> Component.literal("Break [" + index + "] " + shortId(bond.bondId()) + ": " + result.name()), false);
        return result == BondService.BreakResult.BROKEN ? 1 : 0;
    }

    private static int runDismiss(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int index = IntegerArgumentType.getInteger(ctx, "index");
        Bond bond = bondAt(player, index);
        if (bond == null) {
            ctx.getSource().sendFailure(Component.literal("No bond at index " + index + "."));
            return 0;
        }
        BondService.DismissResult result = BondService.dismiss(player, bond.bondId());
        ctx.getSource().sendSuccess(() -> Component.literal("Dismiss [" + index + "] " + shortId(bond.bondId()) + ": " + result.name()), false);
        return result == BondService.DismissResult.DISMISSED ? 1 : 0;
    }

    private static int runActive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String target = StringArgumentType.getString(ctx, "target");
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());

        Optional<UUID> next;
        if (target.equalsIgnoreCase("none") || target.equalsIgnoreCase("clear")) {
            if (!roster.bonds().isEmpty()) {
                ctx.getSource().sendFailure(Component.literal("Cannot clear active pet while you have bonds."));
                return 0;
            }
            next = Optional.empty();
        } else {
            int index;
            try {
                index = Integer.parseInt(target);
            } catch (NumberFormatException ex) {
                ctx.getSource().sendFailure(Component.literal("Expected an index or 'none', got: " + target));
                return 0;
            }
            Bond bond = bondAt(player, index);
            if (bond == null) {
                ctx.getSource().sendFailure(Component.literal("No bond at index " + index + "."));
                return 0;
            }
            next = Optional.of(bond.bondId());
        }

        BondRoster updated = roster.withActive(next);
        player.setData(ModAttachments.BOND_ROSTER.get(), updated);
        ctx.getSource().sendSuccess(() -> Component.literal("Active pet: " + next.map(KindredCommand::shortId).orElse("(none)")), false);
        return 1;
    }

    private static Bond bondAt(ServerPlayer player, int index) {
        BondRoster roster = player.getData(ModAttachments.BOND_ROSTER.get());
        List<Bond> bonds = sortedBonds(roster);
        if (index < 0 || index >= bonds.size()) return null;
        return bonds.get(index);
    }

    private static List<Bond> sortedBonds(BondRoster roster) {
        return roster.bonds().values().stream()
                .sorted(Comparator.comparingLong(Bond::bondedAt))
                .toList();
    }

    private static Entity raycastEntity(ServerPlayer player, double maxDistance) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 reach = eye.add(look.scale(maxDistance));
        AABB box = player.getBoundingBox().expandTowards(look.scale(maxDistance)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, reach, box, e -> !e.isSpectator() && e.isPickable(), maxDistance * maxDistance
        );
        return hit != null ? hit.getEntity() : null;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String describeEntityType(Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key.toString();
    }

    private KindredCommand() {}
}
