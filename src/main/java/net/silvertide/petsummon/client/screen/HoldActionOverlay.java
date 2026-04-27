package net.silvertide.petsummon.client.screen;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.silvertide.petsummon.client.data.HoldActionState;

/**
 * HUD overlay showing the hold-to-confirm progress bar for both DISMISS and SUMMON
 * modes. Registered as a gui layer via RegisterGuiLayersEvent. No-op when
 * {@link HoldActionState} is inactive.
 */
public final class HoldActionOverlay {
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 6;

    private static final int C_BAR_BG = 0xCC000000;
    private static final int C_BAR_TRACK = 0xFF333333;
    private static final int C_BAR_FILL_DISMISS = 0xFFE7B43B;
    private static final int C_BAR_FILL_SUMMON = 0xFF4FA374;
    private static final int C_TEXT = 0xFFFFFFFF;

    public static void render(GuiGraphics g, DeltaTracker delta) {
        if (!HoldActionState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // Tucked above the hotbar / XP bar / health icons rather than smack in the middle
        // of the view. sh - 50 keeps the bar (and its label 12px above) clear of the
        // status bars in survival at all GUI scales.
        int barX = (sw - BAR_WIDTH) / 2;
        int barY = sh - 50;

        Component label = HoldActionState.mode() == HoldActionState.Mode.DISMISS
                ? Component.translatable("petsummon.hud.dismissing")
                : Component.translatable("petsummon.hud.summoning");
        g.drawCenteredString(mc.font, label, sw / 2, barY - 12, C_TEXT);

        // Background border + track
        g.fill(barX - 1, barY - 1, barX + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, C_BAR_BG);
        g.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, C_BAR_TRACK);

        // Fill
        int fillW = (int) (BAR_WIDTH * HoldActionState.progress());
        int fillColor = HoldActionState.mode() == HoldActionState.Mode.DISMISS
                ? C_BAR_FILL_DISMISS
                : C_BAR_FILL_SUMMON;
        g.fill(barX, barY, barX + fillW, barY + BAR_HEIGHT, fillColor);
    }

    private HoldActionOverlay() {}
}
