package net.silvertide.petsummon.client.data;

import net.silvertide.petsummon.network.BondView;

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

    public static void update(List<BondView> newBonds, long globalCooldownRemainingMs) {
        bonds = List.copyOf(newBonds);
        lastUpdatedClientMs = System.currentTimeMillis();
        globalCooldownRemainingMsAtReceive = globalCooldownRemainingMs;
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
    }

    private ClientRosterData() {}
}
