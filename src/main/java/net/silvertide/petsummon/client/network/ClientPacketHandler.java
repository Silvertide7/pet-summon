package net.silvertide.petsummon.client.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.silvertide.petsummon.client.data.ClientRosterData;
import net.silvertide.petsummon.client.data.HoldActionState;
import net.silvertide.petsummon.client.screen.RosterScreen;
import net.silvertide.petsummon.network.packet.S2CCancelHold;
import net.silvertide.petsummon.network.packet.S2CRosterSync;

public final class ClientPacketHandler {
    public static void onRosterSync(S2CRosterSync payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientRosterData.update(payload.bonds(), payload.globalCooldownRemainingMs()));
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
