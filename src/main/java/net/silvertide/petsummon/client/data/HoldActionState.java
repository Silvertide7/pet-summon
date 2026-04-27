package net.silvertide.petsummon.client.data;

import java.util.UUID;

/**
 * Client-side hold-to-confirm timer state for the summon keybind. The keybind handler
 * decides which mode to arm based on whether the active pet is loaded and nearby:
 *
 * <ul>
 *   <li>{@link Mode#DISMISS} — active pet is within {@link #DISMISS_RADIUS} blocks. Bar
 *       reads "Dismissing…"; on completion sends {@code C2SDismissBond(bondId)}.</li>
 *   <li>{@link Mode#SUMMON} — active pet is far/unloaded, or no active set. Bar reads
 *       "Summoning…"; on completion sends {@code C2SSummonByKeybind} (server picks
 *       active or oldest-bonded fallback).</li>
 * </ul>
 *
 * Releasing the key before completion cancels.
 */
public final class HoldActionState {
    public static final double DISMISS_RADIUS = 6.0D;
    public static final double DISMISS_RADIUS_SQ = DISMISS_RADIUS * DISMISS_RADIUS;

    public enum Mode { DISMISS, SUMMON }

    private static Mode mode;
    private static UUID bondId;
    private static long startMs;
    private static long durationMs;

    public static void startDismiss(UUID bondIdArg, long durationMsArg) {
        mode = Mode.DISMISS;
        bondId = bondIdArg;
        startMs = System.currentTimeMillis();
        durationMs = durationMsArg;
    }

    public static void startSummon(long durationMsArg) {
        mode = Mode.SUMMON;
        bondId = null;
        startMs = System.currentTimeMillis();
        durationMs = durationMsArg;
    }

    public static void cancel() {
        mode = null;
        bondId = null;
        startMs = 0L;
        durationMs = 0L;
    }

    public static boolean isActive() {
        return mode != null;
    }

    public static Mode mode() {
        return mode;
    }

    public static UUID bondId() {
        return bondId;
    }

    public static boolean isComplete() {
        return isActive() && (System.currentTimeMillis() - startMs) >= durationMs;
    }

    public static float progress() {
        if (!isActive()) return 0F;
        long elapsed = System.currentTimeMillis() - startMs;
        return Math.min(1F, elapsed / (float) durationMs);
    }

    private HoldActionState() {}
}
