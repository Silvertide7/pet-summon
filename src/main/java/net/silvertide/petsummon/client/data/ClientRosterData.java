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
 * elapsed time locally — bypassing any server↔client clock skew. The
 * {@link BondView#cooldownRemainingMs()} value was the remaining cooldown at the
 * moment the server sent the sync; subtracting elapsed-since-receive gives the
 * up-to-date value with at-most-network-latency error.</p>
 */
public final class ClientRosterData {
    private static List<BondView> bonds = Collections.emptyList();
    private static long lastUpdatedClientMs = 0L;

    public static void update(List<BondView> newBonds) {
        bonds = List.copyOf(newBonds);
        lastUpdatedClientMs = System.currentTimeMillis();
    }

    public static List<BondView> bonds() {
        return bonds;
    }

    public static Optional<BondView> findActive() {
        return bonds.stream().filter(BondView::isActive).findFirst();
    }

    /**
     * Returns the active bond (the keybind's only summon target now). The server
     * maintains an invariant — bonds non-empty ⇒ active set — so this returns empty
     * only when the roster itself is empty.
     */
    public static Optional<BondView> findKeybindSummonTarget() {
        return findActive();
    }

    public static boolean isOnCooldown(BondView bond) {
        long elapsedSinceReceive = System.currentTimeMillis() - lastUpdatedClientMs;
        return elapsedSinceReceive < bond.cooldownRemainingMs();
    }

    public static void clear() {
        bonds = Collections.emptyList();
        lastUpdatedClientMs = 0L;
    }

    private ClientRosterData() {}
}
