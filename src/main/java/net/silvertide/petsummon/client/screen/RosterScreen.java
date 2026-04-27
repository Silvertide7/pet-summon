package net.silvertide.petsummon.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.silvertide.petsummon.client.data.ClientRosterData;
import net.silvertide.petsummon.config.Config;
import net.silvertide.petsummon.network.BondView;
import net.silvertide.petsummon.network.packet.C2SBreakBond;
import net.silvertide.petsummon.network.packet.C2SClaimEntity;
import net.silvertide.petsummon.network.packet.C2SOpenRoster;
import net.silvertide.petsummon.network.packet.C2SSummonBond;
import net.silvertide.petsummon.registry.ModTags;

import java.util.List;
import java.util.UUID;

/**
 * Placeholder roster screen. Procedural rendering, no PNG textures.
 * Lists bonds with per-row Summon and Break buttons. Two-step confirm on Break.
 *
 * Footer surfaces a "Bind this <type>" button when the player is looking at
 * an eligible tamed pet within {@link #CLAIM_RAYCAST_DISTANCE} blocks. Eligibility
 * is checked client-side optimistically; the server re-validates on receipt.
 *
 * Polish (palette, animations, entity preview, validation tooltips) is layered on later.
 */
public final class RosterScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int ROW_HEIGHT = 28;
    private static final int ROW_PAD = 4;
    private static final int FOOTER_H = 32;
    private static final int CLAIM_BTN_H = 20;
    private static final long BREAK_CONFIRM_TTL_MS = 3000L;
    private static final double CLAIM_RAYCAST_DISTANCE = 8.0D;

    // ARGB palette
    private static final int C_BG = 0xCC101418;
    private static final int C_BORDER = 0xFF4A5568;
    private static final int C_ROW_BG = 0xFF1B2128;
    private static final int C_ROW_HOVER = 0xFF263039;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT_MUTED = 0xFF8FA0B0;
    private static final int C_BTN_SUMMON = 0xFF3A7F5A;
    private static final int C_BTN_SUMMON_HOVER = 0xFF4FA374;
    private static final int C_BTN_BREAK = 0xFF7A3A3A;
    private static final int C_BTN_BREAK_HOVER = 0xFF994A4A;
    private static final int C_BTN_BREAK_CONFIRM = 0xFFD45A5A;
    private static final int C_BTN_CLAIM = 0xFF3D5C8A;
    private static final int C_BTN_CLAIM_HOVER = 0xFF5278B0;

    private int leftPos;
    private int topPos;
    private int panelHeight;
    private int rowsTop;
    private int rowsBottom;
    private int claimBtnX;
    private int claimBtnY;
    private int claimBtnW;
    private int scrollOffset = 0;

    private UUID breakArmedBondId = null;
    private long breakArmedExpiresAt = 0L;

    public RosterScreen() {
        super(Component.translatable("petsummon.screen.title"));
    }

    @Override
    protected void init() {
        super.init();
        panelHeight = Math.min(this.height - 40, 6 * ROW_HEIGHT + 24 + FOOTER_H);
        leftPos = (this.width - PANEL_WIDTH) / 2;
        topPos = (this.height - panelHeight) / 2;
        rowsTop = topPos + 24;
        rowsBottom = topPos + panelHeight - FOOTER_H;

        claimBtnW = PANEL_WIDTH - 2 * ROW_PAD - 8;
        claimBtnX = leftPos + ROW_PAD + 4;
        claimBtnY = topPos + panelHeight - FOOTER_H + (FOOTER_H - CLAIM_BTN_H) / 2;

        PacketDistributor.sendToServer(new C2SOpenRoster());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.fill(leftPos, topPos, leftPos + PANEL_WIDTH, topPos + panelHeight, C_BG);
        drawBorder(g, leftPos, topPos, PANEL_WIDTH, panelHeight, C_BORDER);

        g.drawCenteredString(font, getTitle(), leftPos + PANEL_WIDTH / 2, topPos + 8, C_TEXT);

        List<BondView> bonds = ClientRosterData.bonds();
        if (bonds.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("petsummon.screen.empty"),
                    leftPos + PANEL_WIDTH / 2, (rowsTop + rowsBottom) / 2 - 4, C_TEXT_MUTED);
        } else {
            g.enableScissor(leftPos, rowsTop, leftPos + PANEL_WIDTH, rowsBottom);
            for (int i = 0; i < bonds.size(); i++) {
                int rowY = rowsTop + (i - scrollOffset) * ROW_HEIGHT;
                if (rowY + ROW_HEIGHT < rowsTop || rowY > rowsBottom) continue;
                renderRow(g, bonds.get(i), leftPos + ROW_PAD, rowY, PANEL_WIDTH - 2 * ROW_PAD, mouseX, mouseY);
            }
            g.disableScissor();
        }

        renderFooter(g, mouseX, mouseY);
    }

    private void renderFooter(GuiGraphics g, int mouseX, int mouseY) {
        Entity candidate = findClaimCandidate();
        if (candidate != null) {
            boolean hover = inBox(mouseX, mouseY, claimBtnX, claimBtnY, claimBtnW, CLAIM_BTN_H);
            String typeName = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).getPath();
            Component label = Component.translatable("petsummon.screen.bind", typeName);
            drawButton(g, claimBtnX, claimBtnY, claimBtnW, CLAIM_BTN_H, label,
                    hover ? C_BTN_CLAIM_HOVER : C_BTN_CLAIM);
        } else {
            // No candidate — show hint text instead of a button.
            g.drawCenteredString(font, Component.translatable("petsummon.screen.bind_hint"),
                    leftPos + PANEL_WIDTH / 2,
                    claimBtnY + (CLAIM_BTN_H - font.lineHeight) / 2 + 1,
                    C_TEXT_MUTED);
        }
    }

    /**
     * Raycast from the local player. Returns an entity that *might* be claimable —
     * the server's {@link net.silvertide.petsummon.server.BondManager#tryClaim} runs
     * the authoritative gate when the player clicks Bind.
     *
     * Why we don't check owner-match here: {@link AbstractHorse} doesn't sync its
     * owner UUID via SynchedEntityData (only the tamed flag is synced), so on the
     * client {@code getOwnerUUID()} is null for every horse regardless of who tamed
     * it. {@link net.minecraft.world.entity.TamableAnimal} (wolves, cats, parrots)
     * does sync owner UUID, but to keep the gate consistent we let the server make
     * the call. The user-visible cost: looking at a wild horse will show the Bind
     * button; clicking it gets a "Bind failed: NOT_OWNED_BY_PLAYER" chat message.
     *
     * Already-bonded check is also skipped here for the same reason — Bonded
     * attachment isn't synced. Server catches it on the claim packet.
     */
    private Entity findClaimCandidate() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return null;

        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        Vec3 reach = eye.add(look.scale(CLAIM_RAYCAST_DISTANCE));
        AABB box = p.getBoundingBox().expandTowards(look.scale(CLAIM_RAYCAST_DISTANCE)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                p, eye, reach, box,
                e -> !e.isSpectator() && e.isPickable(),
                CLAIM_RAYCAST_DISTANCE * CLAIM_RAYCAST_DISTANCE);
        if (hit == null) return null;
        Entity e = hit.getEntity();

        if (!(e instanceof OwnableEntity)) return null;
        if (BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(e.getType()).is(ModTags.BOND_BLOCKLIST)) return null;
        if (Config.REQUIRE_SADDLEABLE.get() && !(e instanceof Saddleable)) return null;
        if (ClientRosterData.bonds().size() >= Config.MAX_BONDS.get()) return null;

        return e;
    }

    private void renderRow(GuiGraphics g, BondView bond, int x, int y, int w, int mx, int my) {
        int rowH = ROW_HEIGHT - 2;
        boolean rowHover = mx >= x && mx < x + w && my >= y && my < y + rowH;
        g.fill(x, y, x + w, y + rowH, rowHover ? C_ROW_HOVER : C_ROW_BG);

        String name = bond.displayName().orElse(bond.entityType().getPath());
        g.drawString(font, name, x + 6, y + 5, C_TEXT);

        String sub = bond.entityType() + " · " + bond.lastSeenDim().getPath();
        g.drawString(font, sub, x + 6, y + 16, C_TEXT_MUTED);

        int btnH = rowH - 8;
        int btnY = y + 4;
        int summonW = 60;
        int breakW = 50;
        int breakX = x + w - breakW - 4;
        int summonX = breakX - summonW - 4;

        boolean summonHover = inBox(mx, my, summonX, btnY, summonW, btnH);
        boolean breakHover = inBox(mx, my, breakX, btnY, breakW, btnH);
        boolean armed = bond.bondId().equals(breakArmedBondId)
                && System.currentTimeMillis() < breakArmedExpiresAt;

        drawButton(g, summonX, btnY, summonW, btnH,
                Component.translatable("petsummon.screen.summon"),
                summonHover ? C_BTN_SUMMON_HOVER : C_BTN_SUMMON);

        Component breakLabel = armed
                ? Component.translatable("petsummon.screen.break_confirm")
                : Component.translatable("petsummon.screen.break");
        int breakColor = armed
                ? C_BTN_BREAK_CONFIRM
                : (breakHover ? C_BTN_BREAK_HOVER : C_BTN_BREAK);
        drawButton(g, breakX, btnY, breakW, btnH, breakLabel, breakColor);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int mxAll = (int) mouseX;
        int myAll = (int) mouseY;

        // Footer Bind button (only active when there's a valid candidate)
        if (inBox(mxAll, myAll, claimBtnX, claimBtnY, claimBtnW, CLAIM_BTN_H)) {
            Entity candidate = findClaimCandidate();
            if (candidate != null) {
                PacketDistributor.sendToServer(new C2SClaimEntity(candidate.getUUID()));
                return true;
            }
        }

        List<BondView> bonds = ClientRosterData.bonds();
        for (int i = 0; i < bonds.size(); i++) {
            int rowY = rowsTop + (i - scrollOffset) * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < rowsTop || rowY > rowsBottom) continue;

            int x = leftPos + ROW_PAD;
            int w = PANEL_WIDTH - 2 * ROW_PAD;
            int btnH = ROW_HEIGHT - 10;
            int btnY = rowY + 4;
            int summonW = 60;
            int breakW = 50;
            int breakX = x + w - breakW - 4;
            int summonX = breakX - summonW - 4;

            BondView bond = bonds.get(i);
            int mx = (int) mouseX;
            int my = (int) mouseY;

            if (inBox(mx, my, summonX, btnY, summonW, btnH)) {
                PacketDistributor.sendToServer(new C2SSummonBond(bond.bondId()));
                return true;
            }
            if (inBox(mx, my, breakX, btnY, breakW, btnH)) {
                long now = System.currentTimeMillis();
                if (bond.bondId().equals(breakArmedBondId) && now < breakArmedExpiresAt) {
                    PacketDistributor.sendToServer(new C2SBreakBond(bond.bondId()));
                    breakArmedBondId = null;
                } else {
                    breakArmedBondId = bond.bondId();
                    breakArmedExpiresAt = now + BREAK_CONFIRM_TTL_MS;
                }
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int n = ClientRosterData.bonds().size();
        int visibleRows = (rowsBottom - rowsTop) / ROW_HEIGHT;
        int maxOffset = Math.max(0, n - visibleRows);
        if (scrollY > 0) scrollOffset = Math.max(0, scrollOffset - 1);
        else if (scrollY < 0) scrollOffset = Math.min(maxOffset, scrollOffset + 1);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label, int color) {
        g.fill(x, y, x + w, y + h, color);
        g.drawCenteredString(font, label, x + w / 2, y + (h - font.lineHeight) / 2 + 1, C_TEXT);
    }

    private static boolean inBox(int x, int y, int bx, int by, int bw, int bh) {
        return x >= bx && x < bx + bw && y >= by && y < by + bh;
    }
}
