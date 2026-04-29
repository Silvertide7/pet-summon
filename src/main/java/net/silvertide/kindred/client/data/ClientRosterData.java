package net.silvertide.kindred.client.data;

import net.silvertide.kindred.config.Config;
import net.silvertide.kindred.network.BondView;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Client-side cache of the player's bond roster, populated by S2CRosterSync.
 * Read by the roster screen and the keybind handler.
 *
 * <p>Tracks the wall-clock time of the last sync so cooldown checks can measure
 * elapsed time locally — bypasses any server↔client clock skew. Cooldown values were
 * computed at server send time; subtracting elapsed-since-receive yields the
 * up-to-date value with at-most-one-way-network-latency error.</p>
 */
public final class ClientRosterData {
    private static List<BondView> bonds = Collections.emptyList();
    private static long lastUpdatedClientMs = 0L;
    private static long globalCooldownRemainingMsAtReceive = 0L;
    /** -1 means "not synced yet"; readers fall back to {@code Config.MAX_BONDS}
     *  so atCapacity / title-bar checks don't accidentally lock everything out
     *  during the brief window before the first sync arrives. */
    private static int effectiveMaxBonds = -1;

    public static void update(List<BondView> newBonds, long globalCooldownRemainingMs, int effectiveMaxBondsValue) {
        bonds = List.copyOf(newBonds);
        lastUpdatedClientMs = System.currentTimeMillis();
        globalCooldownRemainingMsAtReceive = globalCooldownRemainingMs;
        effectiveMaxBonds = effectiveMaxBondsValue;
    }

    /** Synced effective cap, or the configured hard cap as a fallback before
     *  the first roster sync. Used by the title bar and the bind-candidate
     *  at-capacity check. */
    public static int effectiveMaxBonds() {
        return effectiveMaxBonds < 0 ? Config.MAX_BONDS.get() : effectiveMaxBonds;
    }

    public static List<BondView> bonds() {
        return bonds;
    }

    public static Optional<BondView> findActive() {
        return bonds.stream().filter(BondView::isActive).findFirst();
    }

    /** Returns the active bond — the keybind's only summon target. */
    public static Optional<BondView> findKeybindSummonTarget() {
        return findActive();
    }

    public static boolean isOnCooldown(BondView bond) {
        long elapsedSinceReceive = System.currentTimeMillis() - lastUpdatedClientMs;
        return elapsedSinceReceive < bond.cooldownRemainingMs();
    }

    /** Live remaining per-bond summon cooldown in ms, decayed from the receive-time value. */
    public static long bondCooldownRemainingMsNow(BondView bond) {
        long elapsedSinceReceive = System.currentTimeMillis() - lastUpdatedClientMs;
        return Math.max(0L, bond.cooldownRemainingMs() - elapsedSinceReceive);
    }

    public static boolean isRevivalPending(BondView bond) {
        return revivalRemainingMsNow(bond) > 0L;
    }

    /** Live remaining revival cooldown in ms, decayed from the receive-time value. */
    public static long revivalRemainingMsNow(BondView bond) {
        long elapsedSinceReceive = System.currentTimeMillis() - lastUpdatedClientMs;
        return Math.max(0L, bond.revivalRemainingMs() - elapsedSinceReceive);
    }

    public static boolean isGlobalSummonOnCooldown() {
        return globalCooldownRemainingMsNow() > 0L;
    }

    /** Remaining global cooldown in ms, decayed from the receive-time value by local clock. */
    public static long globalCooldownRemainingMsNow() {
        long elapsedSinceReceive = System.currentTimeMillis() - lastUpdatedClientMs;
        return Math.max(0L, globalCooldownRemainingMsAtReceive - elapsedSinceReceive);
    }

    public static void clear() {
        bonds = Collections.emptyList();
        lastUpdatedClientMs = 0L;
        globalCooldownRemainingMsAtReceive = 0L;
        effectiveMaxBonds = -1;
    }

    private ClientRosterData() {}
}
