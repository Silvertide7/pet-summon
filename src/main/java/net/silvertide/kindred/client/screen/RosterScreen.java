package net.silvertide.kindred.client.screen;

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
import net.silvertide.kindred.client.data.ClientRosterData;
import net.silvertide.kindred.client.data.PreviewEntityCache;
import net.silvertide.kindred.compat.pmmo.PmmoMode;
import net.silvertide.kindred.config.Config;
import net.silvertide.kindred.network.BondView;
import net.silvertide.kindred.network.packet.C2SBreakBond;
import net.silvertide.kindred.network.packet.C2SCheckBindCandidate;
import net.silvertide.kindred.network.packet.C2SClaimEntity;
import net.silvertide.kindred.network.packet.C2SDismissBond;
import net.silvertide.kindred.network.packet.C2SOpenRoster;
import net.silvertide.kindred.network.packet.C2SRenameBond;
import net.silvertide.kindred.network.packet.C2SReorderBond;
import net.silvertide.kindred.network.packet.C2SSetActivePet;
import net.silvertide.kindred.network.packet.C2SSummonBond;
import net.silvertide.kindred.registry.ModTags;

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
    private static final int ROW_HEIGHT = 32;
    private static final int ROW_PAD = 4;

    // Row internal layout: name + buttons share the top line, type · dim sits below.
    private static final int ROW_NAME_Y_OFFSET = 5;
    private static final int ROW_SUBTITLE_Y_OFFSET = 19;

    // Row button layout — kept in one place so renderRow and mouseClicked stay in sync.
    private static final int ROW_BTN_H = 14;
    private static final int ROW_SUMMON_W = 48;
    private static final int ROW_DISMISS_W = 48;
    private static final int ROW_BREAK_W = 14;
    private static final int ROW_BTN_GAP = 4;
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
    private static final int C_BTN_SUMMON_DISABLED = 0xFF22302A;
    private static final int C_BTN_BREAK = 0xFF7A3A3A;
    private static final int C_BTN_BREAK_HOVER = 0xFF994A4A;
    private static final int C_BTN_BREAK_CONFIRM = 0xFFD45A5A;
    private static final int C_BTN_DISMISS = 0xFF6A5A3A;
    private static final int C_BTN_DISMISS_HOVER = 0xFF8A7A52;
    private static final int C_BTN_DISMISS_DISABLED = 0xFF2A2620;
    /** Dimmed label color for disabled buttons — pairs with the darker disabled
     *  background to make "this can't be clicked" obvious at a glance. */
    private static final int C_BTN_TEXT_DISABLED = 0xFF6F6A60;
    private static final int C_BTN_CLAIM = 0xFF3D5C8A;
    private static final int C_BTN_CLAIM_HOVER = 0xFF5278B0;
    private static final int C_STAR_ACTIVE = 0xFFE7B43B;
    private static final int C_STAR_INACTIVE = 0xFF4A5260;
    private static final int C_STAR_HOVER = 0xFF8A95A8;
    /** Radial cooldown indicator: light wedge that drains counter-clockwise as
     *  the cooldown elapses. Drawn centered on the Summon button (replaces the
     *  text label entirely while the cooldown is running). */
    private static final int C_PIE_FILL = 0xFFB0C4D8;
    private static final int C_PIE_RADIUS = 5;

    private static final int STAR_COL_W = 16;
    private static final int ACTION_BTN_H = 14;
    private static final int ACTION_BTN_GAP = 2;
    private static final int PANE_BTN_PAD = 4;
    /** Total vertical space the stacked pane buttons occupy. Three rows: Move Up |
     *  Move Down (split), Rename (full width), Set Active (full width). */
    private static final int PANE_BTN_AREA_H = PANE_BTN_PAD
            + ACTION_BTN_H + ACTION_BTN_GAP
            + ACTION_BTN_H + ACTION_BTN_GAP
            + ACTION_BTN_H + PANE_BTN_PAD;
    /** Top padding inside the preview pane — keeps tall entities (horses, llamas)
     *  from cropping their heads against the panel border. */
    private static final int PREVIEW_TOP_PAD = 16;
    /** Pixels the preview pane content extends below the row list's bottom edge.
     *  Borrows space from the footer area (the bind-hint text is centered across
     *  the panel, so the right side is empty). Pushes both the entity render and
     *  the stacked buttons further down. */
    private static final int PREVIEW_BTM_EXTEND = 14;
    private static final int MAX_NAME_LEN = 32;

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

    /** Active hold-to-confirm timer for a row's Summon, Dismiss, or break Confirm
     *  button. Null when no button is being held. Mirrors the keybind hold behavior
     *  so screen clicks can't bypass the hold gate. */
    private RowHold rowHold = null;

    private enum RowHoldAction { SUMMON, DISMISS, BREAK }

    /** Hold-to-confirm duration for the break-bond Confirm button. Hardcoded (not
     *  configurable) — the X-then-Confirm flow is the destructive action's "are you
     *  sure?" gate, and 1s is enough to prevent slips without feeling sluggish. */
    private static final long BREAK_CONFIRM_HOLD_MS = 1000L;

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

    /** When non-null, that row's name is in inline-edit mode. */
    private UUID renamingBondId = null;
    private String renameBuffer = "";

    /** Snapshotted at {@link #init()} only — never updated while the screen is open.
     *  Server enforces distance on the actual bind packet. */
    private Entity initialCandidate;

    /** Tri-state for the bind candidate's server-side validation:
     *  null = no pending check; FALSE = waiting on response or rejected; TRUE = confirmed.
     *  Owner UUID isn't synced to the client for {@code AbstractHorse}, so we round-trip
     *  the eligibility check through the server before showing the Bind button. */
    private Boolean bindCandidateConfirmed = null;

    /** Translation key for "why can't I bind this", set when the server denies the
     *  candidate. Empty when the candidate is bindable, pending, or there's no
     *  candidate at all. Renders in the footer in place of the generic bind hint. */
    private java.util.Optional<String> bindDenyKey = java.util.Optional.empty();

    public RosterScreen() {
        super(Component.translatable("kindred.screen.title"));
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
        // Constrain the Bind button to the rows column so it doesn't stretch under
        // the preview pane (which has its own buttons in that vertical band).
        claimBtnW = ROW_W;
        claimBtnX = leftPos + ROW_PAD;
        claimBtnY = topPos + panelHeight - FOOTER_H + (FOOTER_H - CLAIM_BTN_H) / 2;

        // Default-select the active pet so the preview shows something on open.
        Optional<BondView> active = ClientRosterData.findActive();
        active.ifPresent(bv -> selectedBondId = bv.bondId());

        // Lock in the bind candidate at open time. No re-raycast while the screen is open.
        // The button stays hidden until the server confirms eligibility — owner UUID
        // isn't synced to the client for AbstractHorse so we can't validate locally.
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            Entity hit = raycastEntity(p);
            if (hit != null && passesClientGates(hit)) {
                initialCandidate = hit;
                bindCandidateConfirmed = Boolean.FALSE;  // pending until server replies
                bindDenyKey = java.util.Optional.empty();
                PacketDistributor.sendToServer(new C2SCheckBindCandidate(hit.getUUID()));
            } else if (hit != null && atCapacityForHit(hit)) {
                // Looking at something that *would* be bondable except the roster is
                // full. Skip the server round-trip and surface the cap message directly.
                bindDenyKey = java.util.Optional.of(capDenyKey());
            } else if (atCapacityNoTarget()) {
                // No bondable target in view, but the player is at their effective
                // cap (or below the PMMO start level). Surface the cap message anyway
                // so the title bar's "X/Y" isn't unexplained — and so LINEAR-mode
                // players see "Next bond unlocks at X" without having to find a pet
                // to aim at.
                bindDenyKey = java.util.Optional.of(capDenyKey());
            }
        }

        PacketDistributor.sendToServer(new C2SOpenRoster());
    }

    /** Called by the network handler when the server returns its eligibility verdict. */
    public void onBindCandidateResult(java.util.UUID entityUUID, boolean canBind, java.util.Optional<String> denyKey) {
        if (initialCandidate == null) return;
        if (!entityUUID.equals(initialCandidate.getUUID())) return;  // stale response
        bindCandidateConfirmed = canBind ? Boolean.TRUE : Boolean.FALSE;
        bindDenyKey = canBind ? java.util.Optional.empty() : denyKey;
        // Keep initialCandidate around even on deny, so the footer can render the
        // type-specific deny message (currently it just uses the lang key as-is, but
        // type-aware messages are an easy follow-up).
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

        // Bond count (e.g. "3/10") — left-aligned in title bar, mirrors the cooldown
        // indicator on the right.
        int bondCount = ClientRosterData.bonds().size();
        int maxBonds = ClientRosterData.effectiveMaxBonds();
        g.drawString(font, bondCount + "/" + maxBonds, leftPos + 6, topPos + 8, C_TEXT_MUTED);

        // Global cooldown indicator (only when active). Right-aligned in title bar.
        if (ClientRosterData.isGlobalSummonOnCooldown()) {
            long remainingMs = ClientRosterData.globalCooldownRemainingMsNow();
            Component text = Component.translatable("kindred.screen.summon_cooldown",
                    formatDurationCoarse(remainingMs));
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
            g.drawCenteredString(font, Component.translatable("kindred.screen.empty"),
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
            g.drawCenteredString(font, Component.translatable("kindred.screen.preview_empty"),
                    previewX + PREVIEW_W / 2, (rowsTop + rowsBottom) / 2 - 4, C_TEXT_MUTED);
            return;
        }

        int paneBottom = previewBottom();
        int entityRenderTop = rowsTop + PREVIEW_TOP_PAD;
        int entityRenderBottom = paneBottom - PANE_BTN_AREA_H;

        LivingEntity entity = PreviewEntityCache.getOrBuild(selected);
        if (entity == null) {
            g.drawCenteredString(font, Component.translatable("kindred.screen.preview_unavailable"),
                    previewX + PREVIEW_W / 2, (entityRenderTop + entityRenderBottom) / 2 - 4, C_TEXT_MUTED);
        } else {
            float w = Math.max(0.1F, entity.getBbWidth());
            float h = Math.max(0.1F, entity.getBbHeight());
            int paneH = entityRenderBottom - entityRenderTop;
            // Tall mobs (horse, llama, camel) have model heads that extend well above
            // their bounding-box height. The 0.5 multiplier on the height-fit pulls
            // their scale down so the head clears the pane top; smaller pets still hit
            // the absolute cap of 60 from the width side.
            int scaleByH = (int) (paneH * 0.5F / h);
            int scaleByW = (int) (PREVIEW_W * 0.7F / w);
            int scale = Math.max(20, Math.min(60, Math.min(scaleByH, scaleByW)));

            // Clamp the mouseY we feed the renderer to a tight band around vertical
            // center. The vanilla helper derives pitch from (centerY - mouseY) / 40,
            // so a cursor above the panel would tilt the head up and out of the box.
            // Yaw still follows the actual mouseX for a bit of life.
            int centerY = (entityRenderTop + entityRenderBottom) / 2;
            int clampedMouseY = Math.max(centerY - 8, Math.min(centerY + 8, (int) mouseY));
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g,
                    previewX, entityRenderTop,
                    previewX + PREVIEW_W, entityRenderBottom,
                    scale,
                    0.0625F,
                    mouseX, clampedMouseY,
                    entity);
        }

        int btnX = previewX + 4;
        int btnW = PREVIEW_W - 8;
        int setActiveBtnY = paneBottom - PANE_BTN_PAD - ACTION_BTN_H;
        int renameBtnY = setActiveBtnY - ACTION_BTN_GAP - ACTION_BTN_H;
        int moveBtnY = renameBtnY - ACTION_BTN_GAP - ACTION_BTN_H;

        // Move Up | Move Down — split row at the top of the stack. Disabled when the
        // selected bond is already at the corresponding edge of the list.
        int moveHalfW = (btnW - ACTION_BTN_GAP) / 2;
        int moveUpX = btnX;
        int moveDownX = btnX + moveHalfW + ACTION_BTN_GAP;
        int moveDownW = btnW - moveHalfW - ACTION_BTN_GAP;

        List<BondView> bonds = ClientRosterData.bonds();
        int bondIdx = -1;
        for (int i = 0; i < bonds.size(); i++) {
            if (bonds.get(i).bondId().equals(selected.bondId())) {
                bondIdx = i;
                break;
            }
        }
        boolean canMoveUp = bondIdx > 0;
        boolean canMoveDown = bondIdx >= 0 && bondIdx < bonds.size() - 1;
        boolean moveUpHover = canMoveUp && inBox(mouseX, mouseY, moveUpX, moveBtnY, moveHalfW, ACTION_BTN_H);
        boolean moveDownHover = canMoveDown && inBox(mouseX, mouseY, moveDownX, moveBtnY, moveDownW, ACTION_BTN_H);
        int moveUpColor = !canMoveUp
                ? C_BTN_DISMISS_DISABLED
                : (moveUpHover ? C_BTN_CLAIM_HOVER : C_BTN_CLAIM);
        int moveDownColor = !canMoveDown
                ? C_BTN_DISMISS_DISABLED
                : (moveDownHover ? C_BTN_CLAIM_HOVER : C_BTN_CLAIM);
        drawButton(g, moveUpX, moveBtnY, moveHalfW, ACTION_BTN_H,
                Component.translatable("kindred.screen.move_up"),
                moveUpColor, 0F, canMoveUp ? C_TEXT : C_BTN_TEXT_DISABLED);
        drawButton(g, moveDownX, moveBtnY, moveDownW, ACTION_BTN_H,
                Component.translatable("kindred.screen.move_down"),
                moveDownColor, 0F, canMoveDown ? C_TEXT : C_BTN_TEXT_DISABLED);

        // Rename button (middle)
        boolean editingThis = selected.bondId().equals(renamingBondId);
        boolean renameHover = !editingThis && inBox(mouseX, mouseY, btnX, renameBtnY, btnW, ACTION_BTN_H);
        int renameColor = editingThis
                ? C_BTN_CLAIM_HOVER  // brighter while in edit mode to signal active state
                : (renameHover ? C_BTN_CLAIM_HOVER : C_BTN_CLAIM);
        drawButton(g, btnX, renameBtnY, btnW, ACTION_BTN_H,
                Component.translatable("kindred.screen.rename"), renameColor);

        // Set Active button (bottom of stack)
        boolean isActive = selected.isActive();
        boolean setActiveHover = !isActive && inBox(mouseX, mouseY, btnX, setActiveBtnY, btnW, ACTION_BTN_H);
        Component setActiveLabel = isActive
                ? Component.translatable("kindred.screen.is_active")
                : Component.translatable("kindred.screen.set_active");
        int setActiveColor = isActive
                ? C_STAR_ACTIVE
                : (setActiveHover ? C_BTN_CLAIM_HOVER : C_BTN_CLAIM);
        drawButton(g, btnX, setActiveBtnY, btnW, ACTION_BTN_H, setActiveLabel, setActiveColor);
    }

    private int previewBottom() {
        return rowsBottom + PREVIEW_BTM_EXTEND;
    }

    private int renameBtnY() {
        return previewBottom() - PANE_BTN_PAD - ACTION_BTN_H - ACTION_BTN_GAP - ACTION_BTN_H;
    }

    private int setActiveBtnY() {
        return previewBottom() - PANE_BTN_PAD - ACTION_BTN_H;
    }

    private int moveBtnY() {
        return renameBtnY() - ACTION_BTN_GAP - ACTION_BTN_H;
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
            int xpCost = Config.BOND_XP_LEVEL_COST.get();
            int btnY = currentBindBtnY();
            // Cost preview line above the Bind button (only when bondXpLevelCost > 0).
            // Soft red when the player can't afford so the visual affordance matches
            // the server's NOT_ENOUGH_XP rejection — they'll click and see the deny
            // message, but the red label is the early signal.
            if (xpCost > 0) {
                LocalPlayer p = Minecraft.getInstance().player;
                boolean canAfford = p == null || p.getAbilities().instabuild || p.experienceLevel >= xpCost;
                int costColor = canAfford ? C_TEXT_MUTED : 0xFFE57878;
                Component costLabel = Component.translatable("kindred.bind.cost", xpCost);
                int costY = topPos + panelHeight - FOOTER_H + 1;
                g.drawCenteredString(font, costLabel, claimBtnX + claimBtnW / 2, costY, costColor);
            }
            boolean hover = inBox(mouseX, mouseY, claimBtnX, btnY, claimBtnW, CLAIM_BTN_H);
            String typeName = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).getPath();
            Component label = Component.translatable("kindred.screen.bind", typeName);
            drawButton(g, claimBtnX, btnY, claimBtnW, CLAIM_BTN_H, label,
                    hover ? C_BTN_CLAIM_HOVER : C_BTN_CLAIM);
            return;
        }
        // No bindable candidate: show the deny reason if the server gave us one,
        // otherwise the generic "look at a tamed pet" hint. Centered in the rows
        // column so the preview pane's buttons stay clear.
        //
        // Three keys take positional args so the player sees actual numbers
        // instead of vague text:
        //   - not_enough_xp:    %1 = required levels
        //   - pmmo_locked:      %1 = skill display name (translated via PMMO's
        //                       pmmo.<skill> lang key), %2 = start level
        //   - at_capacity:      ordinarily the literal "Max bonds reached", but
        //                       in PMMO LINEAR mode with room to grow we swap to
        //                       pmmo_next_unlock with the milestone level so the
        //                       player sees the upgrade path
        Component message = bindDenyKey.map(key -> switch (key) {
            case "kindred.bind.deny.not_enough_xp" ->
                    Component.translatable(key, Config.BOND_XP_LEVEL_COST.get());
            case "kindred.bind.deny.pmmo_locked" -> Component.translatable(
                    key,
                    Component.translatable("pmmo." + Config.PMMO_SKILL.get()),
                    Config.PMMO_START_LEVEL.get());
            case "kindred.bind.deny.at_capacity" -> {
                int currentBonds = ClientRosterData.bonds().size();
                if (Config.PMMO_ENABLED.get()
                        && Config.PMMO_MODE.get() == PmmoMode.LINEAR
                        && currentBonds < Config.MAX_BONDS.get()) {
                    // LINEAR formula: bond N unlocks at startLevel + (N-1) * increment.
                    // Player has currentBonds; next slot unlocks at startLevel +
                    // currentBonds * increment.
                    int nextLevel = Config.PMMO_START_LEVEL.get()
                            + currentBonds * Config.PMMO_INCREMENT_PER_BOND.get();
                    yield Component.translatable("kindred.bind.deny.pmmo_next_unlock",
                            Component.translatable("pmmo." + Config.PMMO_SKILL.get()),
                            nextLevel);
                }
                yield Component.translatable(key);
            }
            default -> Component.translatable(key);
        }).orElse(Component.translatable("kindred.screen.bind_hint"));
        g.drawCenteredString(font, message,
                claimBtnX + claimBtnW / 2,
                claimBtnY + (CLAIM_BTN_H - font.lineHeight) / 2 + 1,
                C_TEXT_MUTED);
    }

    /**
     * Render Y for the Bind button. When {@code bondXpLevelCost > 0}, the button shifts
     * down inside the footer to make room for a cost preview line above it.
     * Otherwise it sits at the centered {@link #claimBtnY} position. Used by both
     * the renderer and the click handler so hit-testing matches the visual.
     */
    private int currentBindBtnY() {
        if (Config.BOND_XP_LEVEL_COST.get() > 0) {
            return topPos + panelHeight - FOOTER_H + 1 + font.lineHeight + 1;
        }
        return claimBtnY;
    }

    private Entity findClaimCandidate() {
        if (initialCandidate != null && initialCandidate.isRemoved()) {
            initialCandidate = null;
            bindCandidateConfirmed = null;
        }
        // Only render the Bind button once the server has confirmed eligibility —
        // hides the button entirely for wild horses (where the local instanceof
        // OwnableEntity check would otherwise pass).
        if (Boolean.TRUE.equals(bindCandidateConfirmed)) return initialCandidate;
        return null;
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
        if (ClientRosterData.bonds().size() >= ClientRosterData.effectiveMaxBonds()) return false;
        return true;
    }

    /**
     * Returns the deny key for the current at-cap state — pmmo_locked when the
     * effective cap is 0 (only reachable via PMMO returning a sub-startLevel
     * skill, since the {@code maxBonds} config has a min of 1), otherwise the
     * regular at_capacity. The renderer further refines at_capacity into the
     * pmmo_next_unlock variant when LINEAR mode has more headroom.
     */
    private static String capDenyKey() {
        return ClientRosterData.effectiveMaxBonds() == 0
                ? "kindred.bind.deny.pmmo_locked"
                : "kindred.bind.deny.at_capacity";
    }

    /** True if the player has no remaining bond slots, regardless of what
     *  they're aiming at. Distinct from {@link #atCapacityForHit}: this one
     *  doesn't care about the entity in view, just the roster size vs. cap. */
    private static boolean atCapacityNoTarget() {
        int cap = ClientRosterData.effectiveMaxBonds();
        return cap == 0 || ClientRosterData.bonds().size() >= cap;
    }

    /**
     * True if the entity would be bindable except the roster is full. Used to skip
     * the server round-trip and surface a specific deny message right at screen
     * open. Owner UUID isn't synced to the client for AbstractHorse, so we can't
     * fully validate ownership here — we just check the cheap gates.
     */
    private boolean atCapacityForHit(Entity e) {
        if (!(e instanceof OwnableEntity)) return false;
        if (BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(e.getType()).is(ModTags.BOND_BLOCKLIST)) return false;
        if (Config.REQUIRE_SADDLEABLE.get() && !(e instanceof Saddleable)) return false;
        return ClientRosterData.bonds().size() >= ClientRosterData.effectiveMaxBonds();
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
        } else if (rowHold.action() == RowHoldAction.DISMISS) {
            btnX = dismissX;
            btnW = dismissW;
        } else {
            // BREAK: confirm button replaces both Dismiss and X while armed,
            // spanning from dismissX out to the right edge.
            btnX = dismissX;
            btnW = rightEdge - dismissX;
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
            switch (action) {
                case SUMMON -> PacketDistributor.sendToServer(new C2SSummonBond(bondId));
                case DISMISS -> PacketDistributor.sendToServer(new C2SDismissBond(bondId));
                case BREAK -> {
                    PacketDistributor.sendToServer(new C2SBreakBond(bondId));
                    breakArmedBondId = null;  // confirm consumed; disarm
                }
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
        String name;
        int nameColor = C_TEXT;
        if (bond.bondId().equals(renamingBondId)) {
            // Inline edit: show the buffer plus a blinking caret.
            boolean caretVisible = (System.currentTimeMillis() / 500L) % 2L == 0L;
            name = renameBuffer + (caretVisible ? "_" : " ");
            nameColor = 0xFFE7B43B;  // gold tint while editing
        } else {
            name = bond.displayName().orElseGet(() -> entityTypeName(bond).getString());
        }
        g.drawString(font, name, textX, y + ROW_NAME_Y_OFFSET, nameColor);

        // Subtitle below the button line: "Horse · Overworld" when loaded,
        // "Horse · Limbo" while revival is pending (dead, respawn-locked),
        // "Horse · Resting" when otherwise dismissed/stored.
        Component stateOrDim;
        if (ClientRosterData.isRevivalPending(bond)) {
            stateOrDim = Component.translatable("kindred.screen.state_limbo");
        } else if (bond.loaded()) {
            stateOrDim = dimensionName(bond.lastSeenDim());
        } else {
            stateOrDim = Component.translatable("kindred.screen.state_resting");
        }
        Component subtitle = entityTypeName(bond).copy().append(" · ").append(stateOrDim);
        g.drawString(font, subtitle, textX, y + ROW_SUBTITLE_Y_OFFSET, C_TEXT_MUTED);

        int btnH = ROW_BTN_H;
        // Buttons align with the top text line, not centered on the full row, so the
        // subtitle reads cleanly below them.
        int btnY = y + 2;
        int rightEdge = x + w - 4;
        int breakSmallX = rightEdge - ROW_BREAK_W;
        int dismissX = breakSmallX - ROW_BTN_GAP - ROW_DISMISS_W;
        int summonX = dismissX - ROW_BTN_GAP - ROW_SUMMON_W;
        int summonW = ROW_SUMMON_W;
        int dismissW = ROW_DISMISS_W;
        int breakSmallW = ROW_BREAK_W;

        // Armed state: TTL gate, OR an in-progress break-confirm hold for this bond
        // (so the Confirm button doesn't disappear mid-hold if the arm TTL expires).
        boolean breakHoldActive = rowHold != null
                && rowHold.bondId().equals(bond.bondId())
                && rowHold.action() == RowHoldAction.BREAK;
        boolean armed = bond.bondId().equals(breakArmedBondId)
                && (System.currentTimeMillis() < breakArmedExpiresAt || breakHoldActive);

        boolean summonDisabled = ClientRosterData.isGlobalSummonOnCooldown()
                || ClientRosterData.isOnCooldown(bond)
                || ClientRosterData.isRevivalPending(bond);
        boolean summonHover = !summonDisabled && inBox(mx, my, summonX, btnY, summonW, btnH);

        // Hold progress (if this row is currently being held for one of these buttons).
        float summonHoldProgress = 0F;
        float dismissHoldProgress = 0F;
        float breakHoldProgress = 0F;
        if (rowHold != null && rowHold.bondId().equals(bond.bondId())) {
            float p = rowHold.progress();
            switch (rowHold.action()) {
                case SUMMON -> summonHoldProgress = p;
                case DISMISS -> dismissHoldProgress = p;
                case BREAK -> breakHoldProgress = p;
            }
        }

        int summonColor = summonDisabled
                ? C_BTN_SUMMON_DISABLED
                : (summonHover ? C_BTN_SUMMON_HOVER : C_BTN_SUMMON);
        // Disabled-by-time states (revival, per-bond cooldown, global cooldown) all
        // share the same UI: no button label, radial sweep centered, precise time on
        // hover. Revival is checked first since "the pet is dead" outranks "rate-
        // limited"; otherwise we pick whichever cooldown has more time left so the
        // wedge matches the actual block.
        long sweepRemainingMs = 0L;
        long sweepTotalMs = 0L;
        Component tooltipText = null;
        Component summonLabel;
        if (ClientRosterData.isRevivalPending(bond)) {
            sweepRemainingMs = ClientRosterData.revivalRemainingMsNow(bond);
            sweepTotalMs = Config.revivalCooldownMs();
            tooltipText = Component.translatable("kindred.screen.respawning",
                    formatDurationCoarse(sweepRemainingMs));
            summonLabel = Component.empty();
        } else if (summonDisabled) {
            long perBondRemaining = ClientRosterData.bondCooldownRemainingMsNow(bond);
            long perBondTotal = Config.SUMMON_COOLDOWN_TICKS.get() * 50L;
            long globalRemaining = ClientRosterData.globalCooldownRemainingMsNow();
            long globalTotal = Config.summonGlobalCooldownMs();
            if (perBondRemaining >= globalRemaining) {
                sweepRemainingMs = perBondRemaining;
                sweepTotalMs = perBondTotal;
            } else {
                sweepRemainingMs = globalRemaining;
                sweepTotalMs = globalTotal;
            }
            tooltipText = Component.literal(formatDurationCoarse(sweepRemainingMs));
            summonLabel = Component.empty();
        } else {
            summonLabel = Component.translatable("kindred.screen.summon");
        }
        int summonTextColor = summonDisabled ? C_BTN_TEXT_DISABLED : C_TEXT;
        drawButton(g, summonX, btnY, summonW, btnH,
                summonLabel,
                summonColor,
                summonHoldProgress,
                summonTextColor);
        if (sweepTotalMs > 0L && sweepRemainingMs > 0L) {
            float progress = Math.min(1F, sweepRemainingMs / (float) sweepTotalMs);
            int pieCx = summonX + summonW / 2;
            int pieCy = btnY + btnH / 2;
            drawRadialSweep(g, pieCx, pieCy, C_PIE_RADIUS, progress, C_PIE_FILL);
            // Long cooldowns (20m+) make the wedge motion imperceptible, so surface
            // precise text on hover. Deferred via setTooltipForNextRenderPass so it
            // renders above the row scissor and any later panel content.
            if (tooltipText != null && inBox(mx, my, summonX, btnY, summonW, btnH)) {
                setTooltipForNextRenderPass(tooltipText);
            }
        }

        if (armed) {
            int confirmX = dismissX;
            int confirmW = rightEdge - confirmX;
            boolean confirmHover = inBox(mx, my, confirmX, btnY, confirmW, btnH);
            drawButton(g, confirmX, btnY, confirmW, btnH,
                    Component.translatable("kindred.screen.break_confirm"),
                    confirmHover ? C_BTN_BREAK_CONFIRM : C_BTN_BREAK_HOVER,
                    breakHoldProgress);
        } else {
            // Dismiss only makes sense for an entity that's actually in the world.
            // Revival-pending pets are dead; not-loaded pets are already stored.
            boolean dismissDisabled = ClientRosterData.isRevivalPending(bond) || !bond.loaded();
            boolean dismissHover = !dismissDisabled && inBox(mx, my, dismissX, btnY, dismissW, btnH);
            boolean breakHover = inBox(mx, my, breakSmallX, btnY, breakSmallW, btnH);

            int dismissColor = dismissDisabled
                    ? C_BTN_DISMISS_DISABLED
                    : (dismissHover ? C_BTN_DISMISS_HOVER : C_BTN_DISMISS);
            int dismissTextColor = dismissDisabled ? C_BTN_TEXT_DISABLED : C_TEXT;
            drawButton(g, dismissX, btnY, dismissW, btnH,
                    Component.translatable("kindred.screen.dismiss"),
                    dismissColor,
                    dismissHoldProgress,
                    dismissTextColor);

            drawButton(g, breakSmallX, btnY, breakSmallW, btnH,
                    Component.literal("X"),
                    breakHover ? C_BTN_BREAK_HOVER : C_BTN_BREAK);
        }
    }

    /**
     * Vanilla-style display name for the bond's entity type, e.g. "Horse" instead of
     * "minecraft:horse". Falls back to the raw resource path when the type isn't in
     * the registry on the client (modded type from a server we don't have the mod for).
     */
    private static Component entityTypeName(BondView bond) {
        var type = BuiltInRegistries.ENTITY_TYPE.get(bond.entityType());
        return type != null ? type.getDescription() : Component.literal(bond.entityType().getPath());
    }

    /**
     * Pretty dimension name. Looks up {@code kindred.dim.<ns>.<path>}; falls back to
     * a capitalized, underscore-stripped version of the resource path so modded
     * dimensions still read reasonably without us shipping every translation.
     */
    private static Component dimensionName(net.minecraft.resources.ResourceLocation dim) {
        String key = "kindred.dim." + dim.getNamespace() + "." + dim.getPath();
        return Component.translatableWithFallback(key, prettifyPath(dim.getPath()));
    }

    private static String prettifyPath(String path) {
        if (path.isEmpty()) return path;
        StringBuilder sb = new StringBuilder(path.length());
        boolean nextUpper = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_') {
                sb.append(' ');
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Coarse human-readable duration: "1h 5m", "5m 30s", or "30s". Rounds up so a
     * sub-second residual still reads as "1s" instead of "0s".
     */
    private static String formatDurationCoarse(long ms) {
        long totalSeconds = (ms + 999L) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    /**
     * Clock-style radial cooldown indicator centered at (cx, cy). The wedge fills the
     * full circle at {@code progress = 1} and shrinks counter-clockwise to nothing as
     * the cooldown elapses (matching MOBA-style ability cooldown convention).
     *
     * <p>Implemented by sampling each pixel in the bounding square and filling the ones
     * inside the disc that fall within the wedge. Cheap at small radii (≤ ~8 px).</p>
     */
    private static void drawRadialSweep(GuiGraphics g, int cx, int cy, int radius, float progress, int color) {
        int r2 = radius * radius;
        double sweepRad = progress * Math.PI * 2.0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy > r2) continue;
                // Angle from 12 o'clock, increasing clockwise. atan2(dx, -dy) gives
                // -PI..PI with 0 = up, positive = right; shift to 0..2PI.
                double angle = Math.atan2(dx, -dy);
                if (angle < 0) angle += Math.PI * 2.0;
                if (angle > sweepRad) continue;
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
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

        if (inBox(mxAll, myAll, claimBtnX, currentBindBtnY(), claimBtnW, CLAIM_BTN_H)) {
            Entity candidate = findClaimCandidate();
            if (candidate != null) {
                PacketDistributor.sendToServer(new C2SClaimEntity(candidate.getUUID()));
                // Clear the candidate immediately so the button disappears on click;
                // server replies via chat for success / failure either way. Avoids
                // showing "Bond to this Wolf" after the wolf is already bonded.
                initialCandidate = null;
                bindCandidateConfirmed = null;
                bindDenyKey = java.util.Optional.empty();
                return true;
            }
        }

        // Pane action buttons: Move Up | Move Down (top), Rename (middle), Set Active
        // (bottom). Click anywhere else commits any in-progress rename first, then
        // proceeds normally.
        BondView selectedView = currentSelection();
        if (selectedView != null) {
            int btnX = previewX + 4;
            int btnW = PREVIEW_W - 8;
            int moveHalfW = (btnW - ACTION_BTN_GAP) / 2;
            int moveDownX = btnX + moveHalfW + ACTION_BTN_GAP;
            int moveDownW = btnW - moveHalfW - ACTION_BTN_GAP;

            // Compute selected bond's position so we can short-circuit clicks on a
            // disabled-edge button (no packet sent if the bond is already at the top
            // for Up, or bottom for Down).
            List<BondView> bonds = ClientRosterData.bonds();
            int bondIdx = -1;
            for (int i = 0; i < bonds.size(); i++) {
                if (bonds.get(i).bondId().equals(selectedView.bondId())) {
                    bondIdx = i;
                    break;
                }
            }

            if (inBox(mxAll, myAll, btnX, moveBtnY(), moveHalfW, ACTION_BTN_H)) {
                if (bondIdx > 0) {
                    if (renamingBondId != null) commitRename();
                    PacketDistributor.sendToServer(new C2SReorderBond(selectedView.bondId(), -1));
                }
                return true;
            }
            if (inBox(mxAll, myAll, moveDownX, moveBtnY(), moveDownW, ACTION_BTN_H)) {
                if (bondIdx >= 0 && bondIdx < bonds.size() - 1) {
                    if (renamingBondId != null) commitRename();
                    PacketDistributor.sendToServer(new C2SReorderBond(selectedView.bondId(), 1));
                }
                return true;
            }

            if (inBox(mxAll, myAll, btnX, renameBtnY(), btnW, ACTION_BTN_H)) {
                if (renamingBondId != null) commitRename();
                startRename(selectedView);
                return true;
            }

            if (!selectedView.isActive()
                    && inBox(mxAll, myAll, btnX, setActiveBtnY(), btnW, ACTION_BTN_H)) {
                if (renamingBondId != null) commitRename();
                PacketDistributor.sendToServer(new C2SSetActivePet(Optional.of(selectedView.bondId())));
                return true;
            }
        }

        // Any other click: commit in-progress rename before continuing.
        if (renamingBondId != null) commitRename();

        List<BondView> bonds = ClientRosterData.bonds();
        for (int i = 0; i < bonds.size(); i++) {
            int rowY = rowsTop + (i - scrollOffset) * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < rowsTop || rowY > rowsBottom) continue;

            int x = leftPos + ROW_PAD;
            int rowH = ROW_HEIGHT - 2;
            int btnH = ROW_BTN_H;
            int btnY = rowY + 2;
            int summonW = ROW_SUMMON_W;
            int dismissW = ROW_DISMISS_W;
            int breakSmallW = ROW_BREAK_W;
            int rightEdge = x + ROW_W - 4;
            int breakSmallX = rightEdge - breakSmallW;
            int dismissX = breakSmallX - ROW_BTN_GAP - dismissW;
            int summonX = dismissX - ROW_BTN_GAP - summonW;

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
                if (ClientRosterData.isRevivalPending(bond)) {
                    if (p != null) p.displayClientMessage(
                            Component.translatable("kindred.summon.reviving"), true);
                    return true;
                }
                if (ClientRosterData.isGlobalSummonOnCooldown()) {
                    if (p != null) p.displayClientMessage(
                            Component.translatable("kindred.summon.global_cooldown"), true);
                    return true;
                }
                if (ClientRosterData.isOnCooldown(bond)) {
                    if (p != null) p.displayClientMessage(
                            Component.translatable("kindred.summon.on_cooldown"), true);
                    return true;
                }
                // Hold-to-confirm. Mirror the keybind contract so screen clicks can't bypass.
                rowHold = new RowHold(bond.bondId(), RowHoldAction.SUMMON,
                        System.currentTimeMillis(), Config.holdToSummonMs());
                return true;
            }

            if (armed) {
                int confirmX = dismissX;
                int confirmW = rightEdge - confirmX;
                if (inBox(mx, my, confirmX, btnY, confirmW, btnH)) {
                    // Hold-to-confirm — same UX as Summon/Dismiss. Mouse-up before
                    // the hold completes cancels (handled in mouseReleased).
                    rowHold = new RowHold(bond.bondId(), RowHoldAction.BREAK,
                            System.currentTimeMillis(), BREAK_CONFIRM_HOLD_MS);
                    return true;
                }
            } else {
                if (inBox(mx, my, dismissX, btnY, dismissW, btnH)) {
                    // Block the hold for any state where there's nothing to dismiss:
                    // dead (revival pending) or already stored (not loaded).
                    if (ClientRosterData.isRevivalPending(bond) || !bond.loaded()) return true;
                    rowHold = new RowHold(bond.bondId(), RowHoldAction.DISMISS,
                            System.currentTimeMillis(), Config.holdToDismissMs());
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
    public boolean charTyped(char codePoint, int modifiers) {
        if (renamingBondId == null) return super.charTyped(codePoint, modifiers);
        if (codePoint == '§' || Character.isISOControl(codePoint)) return true;
        if (renameBuffer.length() < MAX_NAME_LEN) {
            renameBuffer = renameBuffer + codePoint;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renamingBondId != null) {
            // Enter (257) / Numpad Enter (335) → commit.
            if (keyCode == 257 || keyCode == 335) {
                commitRename();
                return true;
            }
            // Escape (256) → cancel without saving.
            if (keyCode == 256) {
                cancelRename();
                return true;
            }
            // Backspace (259) → delete last char.
            if (keyCode == 259) {
                if (!renameBuffer.isEmpty()) {
                    renameBuffer = renameBuffer.substring(0, renameBuffer.length() - 1);
                }
                return true;
            }
            // Swallow other keys so we don't trigger global keybinds while editing.
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void startRename(BondView bond) {
        renamingBondId = bond.bondId();
        renameBuffer = bond.displayName().orElse("");
    }

    private void commitRename() {
        if (renamingBondId == null) return;
        String trimmed = renameBuffer.trim();
        Optional<String> newName = trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
        PacketDistributor.sendToServer(new C2SRenameBond(renamingBondId, newName));
        renamingBondId = null;
        renameBuffer = "";
    }

    private void cancelRename() {
        renamingBondId = null;
        renameBuffer = "";
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
        drawButton(g, x, y, w, h, label, color, 0F, C_TEXT);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label, int color, float holdProgress) {
        drawButton(g, x, y, w, h, label, color, holdProgress, C_TEXT);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label, int color, float holdProgress, int textColor) {
        g.fill(x, y, x + w, y + h, color);
        if (holdProgress > 0F) {
            int fillW = Math.max(1, (int) (w * holdProgress));
            g.fill(x, y, x + fillW, y + h, 0x66FFFFFF);
        }
        g.drawCenteredString(font, label, x + w / 2, y + (h - font.lineHeight) / 2 + 1, textColor);
    }

    private static boolean inBox(int x, int y, int bx, int by, int bw, int bh) {
        return x >= bx && x < bx + bw && y >= by && y < by + bh;
    }
}
