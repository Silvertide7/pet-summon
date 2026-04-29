package net.silvertide.kindred.compat.pmmo;

/**
 * How the PMMO skill gate maps a player's level to their effective bond cap.
 *
 * <ul>
 *   <li><b>ALL_OR_NOTHING</b>: at the configured start level, all bonds (up to
 *       {@code maxBonds}) unlock at once. Below the start level, zero bonds.</li>
 *   <li><b>LINEAR</b>: 1 bond at the start level; one additional bond every
 *       {@code incrementPerBond} levels above. Capped by {@code maxBonds}.</li>
 * </ul>
 */
public enum PmmoMode {
    ALL_OR_NOTHING,
    LINEAR
}
