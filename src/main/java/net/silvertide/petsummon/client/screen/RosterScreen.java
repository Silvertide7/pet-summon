package net.silvertide.petsummon.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.silvertide.petsummon.client.data.ClientRosterData;
import net.silvertide.petsummon.client.data.PreviewEntityCache;
import net.silvertide.petsummon.config.Config;
import net.silvertide.petsummon.network.BondView;
import net.silvertide.petsummon.network.packet.C2SBreakBond;
import net.silvertide.petsummon.network.packet.C2SClaimEntity;
import net.silvertide.petsummon.network.packet.C2SDismissBond;
import net.silvertide.petsummon.network.packet.C2SOpenRoster;
import net.silvertide.petsummon.network.packet.C2SSetActivePet;
import net.silvertide.petsummon.network.packet.C2SSummonBond;
import net.silvertide.petsummon.registry.ModTags;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Roster screen with two-column layout: row list on the left, entity preview pane on
 * the right. Clicking anywhere on a row body (not on a button/star) selects it,
 * driving which pet renders in the preview pane. Default selection on open is the
 * active pet.
 *
 * <p>Preview rendering goes through {@link InventoryScreen#renderEntityInInventoryFollowsMouse}
 * with {@link LivingEntity} instances built lazily by {@link PreviewEntityCache} from
 * each bond's snapshot NBT.</p>
 */
public final class RosterScreen extends Screen {
    private static final int PANEL_WIDTH = 400;
    private static final int ROW_W = 280;
    private static final int PREVIEW_W = 100;
    private static final int ROW_HEIGHT = 28;
    private static final int ROW_PAD = 4;
    private static final int FOOTER_H = 32;
    private static final int CLAIM_BTN_H = 20;
    private static final long BREAK_CONFIRM_TTL_MS = 3000L;
    private static final double CLAIM_RAYCAST_DISTANCE = 8.0D;

    // ARGB palette
    private static final int C_BG = 0xCC101418;
    private static final int C_BORDER = 0xFF4A5568;
    private static final int C_SEPARATOR = 0xFF2A323C;
    private static final int C_ROW_BG = 0xFF1B2128;
    private static final int C_ROW_HOVER = 0xFF263039;
    private static final int C_ROW_SELECTED = 0xFF2D3947;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT_MUTED = 0xFF8FA0B0;
    private static final int C_BTN_SUMMON = 0xFF3A7F5A;
    private static final int C_BTN_SUMMON_HOVER = 0xFF4FA374;
    private static final int C_BTN_SUMMON_DISABLED = 0xFF3F4F45;
    private static final int C_BTN_BREAK = 0xFF7A3A3A;
    private static final int C_BTN_BREAK_HOVER = 0xFF994A4A;
    private static final int C_BTN_BREAK_CONFIRM = 0xFFD45A5A;
    private static final int C_BTN_DISMISS = 0xFF6A5A3A;
    private static final int C_BTN_DISMISS_HOVER = 0xFF8A7A52;
    private static final int C_BTN_CLAIM = 0xFF3D5C8A;
    private static final int C_BTN_CLAIM_HOVER = 0xFF5278B0;
    private static final int C_STAR_ACTIVE = 0xFFE7B43B;
    private static final int C_STAR_INACTIVE = 0xFF4A5260;
    private static final int C_STAR_HOVER = 0xFF8A95A8;

    private static final int STAR_COL_W = 16;
    private static final int SET_ACTIVE_BTN_H = 14;
    private static final int SET_ACTIVE_BTN_PAD = 4;

    private int leftPos;
    private int topPos;
    private int panelHeight;
    private int rowsTop;
    private int rowsBottom;
    private int previewX;       // left of preview pane (relative to screen)
    private int separatorX;     // 1px vertical separator x
    private int claimBtnX;
    private int claimBtnY;
    private int claimBtnW;
    private int scrollOffset = 0;

    private UUID breakArmedBondId = null;
    private long breakArmedExpiresAt = 0L;

    /** Active hold-to-confirm timer for a row's Summon or Dismiss button. Null when no
     *  button is being held. Mirrors the keybind hold behavior so screen clicks can't
     *  bypass the hold gate. */
    private RowHold rowHold = null;

    private enum RowHoldAction { SUMMON, DISMISS }

    private record RowHold(UUID bondId, RowHoldAction action, long startMs, long durationMs) {
        boolean isComplete() {
            return System.currentTimeMillis() - startMs >= durationMs;
        }

        float progress() {
            long elapsed = System.currentTimeMillis() - startMs;
            return Math.min(1F, elapsed / (float) durationMs);
        }
    }

    /** Bond shown in the preview pane. Null until first sync. Defaults to the active
     *  pet on open; clicking a row body switches it. */
    private UUID selectedBondId = null;

    /** Snapshotted at {@link #init()} only — never updated while the screen is open.
     *  Server enforces distance on the actual bind packet. */
    private Entity initialCandidate;

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

        // Layout: [4 pad][ROW_W rows][4 gap][1 separator][3 gap][PREVIEW_W preview][8 pad]
        separatorX = leftPos + ROW_PAD + ROW_W + 4;
        previewX = separatorX + 4;

        // Claim/bind button spans full panel width minus padding (footer is below preview).
        claimBtnW = PANEL_WIDTH - 2 * ROW_PAD - 8;
        claimBtnX = leftPos + ROW_PAD + 4;
        claimBtnY = topPos + panelHeight - FOOTER_H + (FOOTER_H - CLAIM_BTN_H) / 2;

        // Default-select the active pet so the preview shows something on open.
        Optional<BondView> active = ClientRosterData.findActive();
        active.ifPresent(bv -> selectedBondId = bv.bondId());

        // Lock in the bind candidate at open time. No re-raycast while the screen is open.
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            Entity hit = raycastEntity(p);
            if (hit != null && passesClientGates(hit)) {
                initialCandidate = hit;
            }
        }

        PacketDistributor.sendToServer(new C2SOpenRoster());
    }

    @Override
    public void removed() {
        super.removed();
        PreviewEntityCache.clear();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Process the active row-hold (if any): cancel on drag-off / row gone, fire on
        // completion. Done before drawing so the buttons render in their post-fire state
        // when the hold completes mid-frame.
        processRowHold(mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);

        g.fill(leftPos, topPos, leftPos + PANEL_WIDTH, topPos + panelHeight, C_BG);
        drawBorder(g, leftPos, topPos, PANEL_WIDTH, panelHeight, C_BORDER);

        g.drawCenteredString(font, getTitle(), leftPos + PANEL_WIDTH / 2, topPos + 8, C_TEXT);

        // Global cooldown indicator (only when active). Right-aligned in title bar.
        if (ClientRosterData.isGlobalSummonOnCooldown()) {
            long remainingMs = ClientRosterData.globalCooldownRemainingMsNow();
            String text = String.format("Cooldown: %.1fs", remainingMs / 1000.0F);
            int tw = font.width(text);
            g.drawString(font, text, leftPos + PANEL_WIDTH - 6 - tw, topPos + 8, C_TEXT_MUTED);
        }

        // Vertical separator between rows column and preview pane
        g.fill(separatorX, rowsTop, separatorX + 1, rowsBottom, C_SEPARATOR);

        List<BondView> bonds = ClientRosterData.bonds();
        // Recover from invalidated selection (broken bond): fall back to active or first.
        if (selectedBondId != null && bonds.stream().noneMatch(b -> b.bondId().equals(selectedBondId))) {
            selectedBondId = ClientRosterData.findActive()
                    .map(BondView::bondId)
                    .orElseGet(() -> bonds.isEmpty() ? null : bonds.get(0).bondId());
        }

        if (bonds.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("petsummon.screen.empty"),
                    leftPos + ROW_PAD + ROW_W / 2, (rowsTop + rowsBottom) / 2 - 4, C_TEXT_MUTED);
        } else {
            int rowsLeft = leftPos + ROW_PAD;
            g.enableScissor(rowsLeft, rowsTop, rowsLeft + ROW_W, rowsBottom);
            for (int i = 0; i < bonds.size(); i++) {
                int rowY = rowsTop + (i - scrollOffset) * ROW_HEIGHT;
                if (rowY + ROW_HEIGHT < rowsTop || rowY > rowsBottom) continue;
                renderRow(g, bonds.get(i), rowsLeft, rowY, ROW_W, mouseX, mouseY);
            }
            g.disableScissor();
        }

        renderPreviewPane(g, mouseX, mouseY);
        renderFooter(g, mouseX, mouseY);
    }

    private void renderPreviewPane(GuiGraphics g, int mouseX, int mouseY) {
        BondView selected = currentSelection();
        if (selected == null) {
            g.drawCenteredString(font, Component.translatable("petsummon.screen.preview_empty"),
                    previewX + PREVIEW_W / 2, (rowsTop + rowsBottom) / 2 - 4, C_TEXT_MUTED);
            return;
        }

        // Reserve space at the bottom of the pane for the Set Active button.
        int entityRenderBottom = rowsBottom - SET_ACTIVE_BTN_H - SET_ACTIVE_BTN_PAD * 2;

        LivingEntity entity = PreviewEntityCache.getOrBuild(selected);
        if (entity == null) {
            g.drawCenteredString(font, Component.translatable("petsummon.screen.preview_unavailable"),
                    previewX + PREVIEW_W / 2, (rowsTop + entityRenderBottom) / 2 - 4, C_TEXT_MUTED);
        } else {
            // Adaptive scale: fit ~70% of the smaller pane axis to the entity's bounding box.
            float w = Math.max(0.1F, entity.getBbWidth());
            float h = Math.max(0.1F, entity.getBbHeight());
            int paneH = entityRenderBottom - rowsTop;
            int scaleByH = (int) (paneH * 0.7F / h);
            int scaleByW = (int) (PREVIEW_W * 0.7F / w);
            int scale = Math.max(20, Math.min(60, Math.min(scaleByH, scaleByW)));

            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g,
                    previewX, rowsTop,
                    previewX + PREVIEW_W, entityRenderBottom,
                    scale,
                    0.0625F,
                    mouseX, mouseY,
                    entity);
        }

        // Set Active button at the bottom of the pane.
        int btnX = previewX + 4;
        int btnY = rowsBottom - SET_ACTIVE_BTN_H - SET_ACTIVE_BTN_PAD;
        int btnW = PREVIEW_W - 8;
        boolean isActive = selected.isActive();
        boolean hover = !isActive && inBox(mouseX, mouseY, btnX, btnY, btnW, SET_ACTIVE_BTN_H);
        Component label = isActive
                ? Component.translatable("petsummon.screen.is_active")
                : Component.translatable("petsummon.screen.set_active");
        int color = isActive
                ? C_STAR_ACTIVE
                : (hover ? C_BTN_CLAIM_HOVER : C_BTN_CLAIM);
        drawButton(g, btnX, btnY, btnW, SET_ACTIVE_BTN_H, label, color);
    }

    private BondView currentSelection() {
        if (selectedBondId == null) return null;
        for (BondView b : ClientRosterData.bonds()) {
            if (b.bondId().equals(selectedBondId)) return b;
        }
        return null;
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
            g.drawCenteredString(font, Component.translatable("petsummon.screen.bind_hint"),
                    leftPos + PANEL_WIDTH / 2,
                    claimBtnY + (CLAIM_BTN_H - font.lineHeight) / 2 + 1,
                    C_TEXT_MUTED);
        }
    }

    private Entity findClaimCandidate() {
        if (initialCandidate != null && initialCandidate.isRemoved()) {
            initialCandidate = null;
        }
        return initialCandidate;
    }

    private Entity raycastEntity(LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        Vec3 reach = eye.add(look.scale(CLAIM_RAYCAST_DISTANCE));
        AABB box = p.getBoundingBox().expandTowards(look.scale(CLAIM_RAYCAST_DISTANCE)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                p, eye, reach, box,
                e -> !e.isSpectator() && e.isPickable(),
                CLAIM_RAYCAST_DISTANCE * CLAIM_RAYCAST_DISTANCE);
        return hit != null ? hit.getEntity() : null;
    }

    private boolean passesClientGates(Entity e) {
        if (!(e instanceof OwnableEntity)) return false;
        if (BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(e.getType()).is(ModTags.BOND_BLOCKLIST)) return false;
        if (Config.REQUIRE_SADDLEABLE.get() && !(e instanceof Saddleable)) return false;
        if (ClientRosterData.bonds().size() >= Config.MAX_BONDS.get()) return false;
        return true;
    }

    private void processRowHold(int mouseX, int mouseY) {
        if (rowHold == null) return;

        // Locate the held row in the (possibly resorted) bond list.
        List<BondView> bonds = ClientRosterData.bonds();
        int rowIndex = -1;
        for (int i = 0; i < bonds.size(); i++) {
            if (bonds.get(i).bondId().equals(rowHold.bondId())) {
                rowIndex = i;
                break;
            }
        }
        if (rowIndex < 0) {
            rowHold = null;
            return;
        }

        int rowY = rowsTop + (rowIndex - scrollOffset) * ROW_HEIGHT;
        if (rowY + ROW_HEIGHT - 2 <= rowsTop || rowY >= rowsBottom) {
            // Scrolled out of view — cancel.
            rowHold = null;
            return;
        }

        int x = leftPos + ROW_PAD;
        int btnH = ROW_HEIGHT - 10;
        int btnY = rowY + 4;
        int summonW = 50;
        int dismissW = 50;
        int breakSmallW = 16;
        int rightEdge = x + ROW_W - 4;
        int breakSmallX = rightEdge - breakSmallW;
        int dismissX = breakSmallX - dismissW - 4;
        int summonX = dismissX - summonW - 4;

        int btnX;
        int btnW;
        if (rowHold.action() == RowHoldAction.SUMMON) {
            btnX = summonX;
            btnW = summonW;
        } else {
            btnX = dismissX;
            btnW = dismissW;
        }

        if (!inBox(mouseX, mouseY, btnX, btnY, btnW, btnH)) {
            // Mouse drifted off the button — cancel.
            rowHold = null;
            return;
        }

        if (rowHold.isComplete()) {
            UUID bondId = rowHold.bondId();
            RowHoldAction action = rowHold.action();
            rowHold = null;
            if (action == RowHoldAction.SUMMON) {
                PacketDistributor.sendToServer(new C2SSummonBond(bondId));
            } else {
                PacketDistributor.sendToServer(new C2SDismissBond(bondId));
            }
        }
    }

    private void renderRow(GuiGraphics g, BondView bond, int x, int y, int w, int mx, int my) {
        int rowH = ROW_HEIGHT - 2;
        boolean rowHover = mx >= x && mx < x + w && my >= y && my < y + rowH;
        boolean selected = bond.bondId().equals(selectedBondId);
        int rowBg = selected ? C_ROW_SELECTED : (rowHover ? C_ROW_HOVER : C_ROW_BG);
        g.fill(x, y, x + w, y + rowH, rowBg);

        // Diamond is a visual indicator only — clicking it does nothing now. Active is
        // set via the "Set Active" button under the preview pane to avoid mis-clicks.
        int starCx = x + STAR_COL_W / 2;
        int starCy = y + rowH / 2;
        int starColor = bond.isActive() ? C_STAR_ACTIVE : C_STAR_INACTIVE;
        drawStar(g, starCx, starCy, starColor);

        int textX = x + STAR_COL_W + 4;
        String name = bond.displayName().orElse(bond.entityType().getPath());
        g.drawString(font, name, textX, y + 5, C_TEXT);

        String sub = bond.entityType() + " · " + bond.lastSeenDim().getPath();
        g.drawString(font, sub, textX, y + 16, C_TEXT_MUTED);

        int btnH = rowH - 8;
        int btnY = y + 4;
        int summonW = 50;
        int dismissW = 50;
        int breakSmallW = 16;
        int rightEdge = x + w - 4;

        int breakSmallX = rightEdge - breakSmallW;
        int dismissX = breakSmallX - dismissW - 4;
        int summonX = dismissX - summonW - 4;

        boolean armed = bond.bondId().equals(breakArmedBondId)
                && System.currentTimeMillis() < breakArmedExpiresAt;

        boolean summonDisabled = ClientRosterData.isGlobalSummonOnCooldown()
                || ClientRosterData.isOnCooldown(bond);
        boolean summonHover = !summonDisabled && inBox(mx, my, summonX, btnY, summonW, btnH);

        // Hold progress (if this row is currently being held for one of these buttons).
        float summonHoldProgress = 0F;
        float dismissHoldProgress = 0F;
        if (rowHold != null && rowHold.bondId().equals(bond.bondId())) {
            float p = rowHold.progress();
            if (rowHold.action() == RowHoldAction.SUMMON) summonHoldProgress = p;
            else dismissHoldProgress = p;
        }

        int summonColor = summonDisabled
                ? C_BTN_SUMMON_DISABLED
                : (summonHover ? C_BTN_SUMMON_HOVER : C_BTN_SUMMON);
        drawButton(g, summonX, btnY, summonW, btnH,
                Component.translatable("petsummon.screen.summon"),
                summonColor,
                summonHoldProgress);

        if (armed) {
            int confirmX = dismissX;
            int confirmW = rightEdge - confirmX;
            boolean confirmHover = inBox(mx, my, confirmX, btnY, confirmW, btnH);
            drawButton(g, confirmX, btnY, confirmW, btnH,
                    Component.translatable("petsummon.screen.break_confirm"),
                    confirmHover ? C_BTN_BREAK_CONFIRM : C_BTN_BREAK_HOVER);
        } else {
            boolean dismissHover = inBox(mx, my, dismissX, btnY, dismissW, btnH);
            boolean breakHover = inBox(mx, my, breakSmallX, btnY, breakSmallW, btnH);

            drawButton(g, dismissX, btnY, dismissW, btnH,
                    Component.translatable("petsummon.screen.dismiss"),
                    dismissHover ? C_BTN_DISMISS_HOVER : C_BTN_DISMISS,
                    dismissHoldProgress);

            drawButton(g, breakSmallX, btnY, breakSmallW, btnH,
                    Component.literal("X"),
                    breakHover ? C_BTN_BREAK_HOVER : C_BTN_BREAK);
        }
    }

    /** 5x5 procedural diamond ("star" stand-in), centered at (cx, cy). */
    private static void drawStar(GuiGraphics g, int cx, int cy, int color) {
        g.fill(cx,     cy - 2, cx + 1, cy - 1, color);
        g.fill(cx - 1, cy - 1, cx + 2, cy,     color);
        g.fill(cx - 2, cy,     cx + 3, cy + 1, color);
        g.fill(cx - 1, cy + 1, cx + 2, cy + 2, color);
        g.fill(cx,     cy + 2, cx + 1, cy + 3, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int mxAll = (int) mouseX;
        int myAll = (int) mouseY;

        if (inBox(mxAll, myAll, claimBtnX, claimBtnY, claimBtnW, CLAIM_BTN_H)) {
            Entity candidate = findClaimCandidate();
            if (candidate != null) {
                PacketDistributor.sendToServer(new C2SClaimEntity(candidate.getUUID()));
                return true;
            }
        }

        // "Set Active" button under the preview pane.
        BondView selectedView = currentSelection();
        if (selectedView != null && !selectedView.isActive()) {
            int btnX = previewX + 4;
            int btnY = rowsBottom - SET_ACTIVE_BTN_H - SET_ACTIVE_BTN_PAD;
            int btnW = PREVIEW_W - 8;
            if (inBox(mxAll, myAll, btnX, btnY, btnW, SET_ACTIVE_BTN_H)) {
                PacketDistributor.sendToServer(new C2SSetActivePet(Optional.of(selectedView.bondId())));
                return true;
            }
        }

        List<BondView> bonds = ClientRosterData.bonds();
        for (int i = 0; i < bonds.size(); i++) {
            int rowY = rowsTop + (i - scrollOffset) * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < rowsTop || rowY > rowsBottom) continue;

            int x = leftPos + ROW_PAD;
            int rowH = ROW_HEIGHT - 2;
            int btnH = ROW_HEIGHT - 10;
            int btnY = rowY + 4;
            int summonW = 50;
            int dismissW = 50;
            int breakSmallW = 16;
            int rightEdge = x + ROW_W - 4;
            int breakSmallX = rightEdge - breakSmallW;
            int dismissX = breakSmallX - dismissW - 4;
            int summonX = dismissX - summonW - 4;

            BondView bond = bonds.get(i);
            int mx = (int) mouseX;
            int my = (int) mouseY;
            long now = System.currentTimeMillis();
            boolean armed = bond.bondId().equals(breakArmedBondId) && now < breakArmedExpiresAt;

            // Skip rows whose vertical area the click isn't on.
            if (my < rowY || my >= rowY + rowH) continue;
            // And whose horizontal area the click isn't on.
            if (mx < x || mx >= x + ROW_W) continue;

            if (inBox(mx, my, summonX, btnY, summonW, btnH)) {
                // Pre-check cooldowns to avoid wasting a full hold for a guaranteed rejection.
                LocalPlayer p = Minecraft.getInstance().player;
                if (ClientRosterData.isGlobalSummonOnCooldown()) {
                    if (p != null) p.displayClientMessage(
                            Component.translatable("petsummon.summon.global_cooldown"), true);
                    return true;
                }
                if (ClientRosterData.isOnCooldown(bond)) {
                    if (p != null) p.displayClientMessage(
                            Component.translatable("petsummon.summon.on_cooldown"), true);
                    return true;
                }
                // Hold-to-confirm. Mirror the keybind contract so screen clicks can't bypass.
                rowHold = new RowHold(bond.bondId(), RowHoldAction.SUMMON,
                        System.currentTimeMillis(), Config.HOLD_TO_SUMMON_MS.get());
                return true;
            }

            if (armed) {
                int confirmX = dismissX;
                int confirmW = rightEdge - confirmX;
                if (inBox(mx, my, confirmX, btnY, confirmW, btnH)) {
                    PacketDistributor.sendToServer(new C2SBreakBond(bond.bondId()));
                    breakArmedBondId = null;
                    return true;
                }
            } else {
                if (inBox(mx, my, dismissX, btnY, dismissW, btnH)) {
                    rowHold = new RowHold(bond.bondId(), RowHoldAction.DISMISS,
                            System.currentTimeMillis(), Config.HOLD_TO_DISMISS_MS.get());
                    return true;
                }
                if (inBox(mx, my, breakSmallX, btnY, breakSmallW, btnH)) {
                    breakArmedBondId = bond.bondId();
                    breakArmedExpiresAt = now + BREAK_CONFIRM_TTL_MS;
                    return true;
                }
            }

            // Click landed on this row's body (not on any interactive control). Select it.
            selectedBondId = bond.bondId();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && rowHold != null) {
            rowHold = null;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Called by the cancel-hold packet handler when the player takes damage. */
    public void cancelRowHold() {
        rowHold = null;
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
        drawButton(g, x, y, w, h, label, color, 0F);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label, int color, float holdProgress) {
        g.fill(x, y, x + w, y + h, color);
        if (holdProgress > 0F) {
            int fillW = Math.max(1, (int) (w * holdProgress));
            g.fill(x, y, x + fillW, y + h, 0x66FFFFFF);
        }
        g.drawCenteredString(font, label, x + w / 2, y + (h - font.lineHeight) / 2 + 1, C_TEXT);
    }

    private static boolean inBox(int x, int y, int bx, int by, int bw, int bh) {
        return x >= bx && x < bx + bw && y >= by && y < by + bh;
    }
}
