package net.silvertide.kindred.client.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.silvertide.kindred.client.data.ClientRosterData;
import net.silvertide.kindred.client.data.HoldActionState;
import net.silvertide.kindred.client.data.PreviewEntityCache;
import net.silvertide.kindred.client.screen.RosterScreen;
import net.silvertide.kindred.network.packet.S2CBindCandidateResult;
import net.silvertide.kindred.network.packet.S2CCancelHold;
import net.silvertide.kindred.network.packet.S2CRosterSync;

public final class ClientPacketHandler {
    public static void onRosterSync(S2CRosterSync payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientRosterData.update(payload.bonds(), payload.globalCooldownRemainingMs());
            // Invalidate the preview entity cache — any pet's NBT may have changed
            // (saddle, armor, chest contents, age) and the cached LivingEntity instance
            // was built from the previous snapshot. Next render rebuilds from fresh NBT.
            PreviewEntityCache.clear();
        });
    }

    public static void onBindCandidateResult(S2CBindCandidateResult payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Screen current = Minecraft.getInstance().screen;
            if (current instanceof RosterScreen rs) {
                rs.onBindCandidateResult(payload.entityUUID(), payload.canBind(), payload.denyMessageKey());
            }
        });
    }

    public static void onCancelHold(S2CCancelHold payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Cancel keybind hold (if active).
            HoldActionState.cancel();
            // Cancel screen row-button hold (if a roster screen is currently open).
            Screen current = Minecraft.getInstance().screen;
            if (current instanceof RosterScreen rs) {
                rs.cancelRowHold();
            }
        });
    }

    private ClientPacketHandler() {}
}
