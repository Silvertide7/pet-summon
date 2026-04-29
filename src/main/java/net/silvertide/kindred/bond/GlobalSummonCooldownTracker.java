package net.silvertide.kindred.bond;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player wall-clock timestamp of the last successful summon. Used to enforce
 * {@code summonGlobalCooldownMs} — a roster-wide cooldown that prevents spam-summoning
 * across different bonds. Distinct from {@code summonCooldownTicks} which is per-bond.
 *
 * <p>Transient — not persisted. Cleared implicitly on server stop. Entries are kept on
 * logout so the cooldown isn't bypassable by reconnecting.</p>
 */
public final class GlobalSummonCooldownTracker {
    private static final GlobalSummonCooldownTracker INSTANCE = new GlobalSummonCooldownTracker();

    public static GlobalSummonCooldownTracker get() {
        return INSTANCE;
    }

    private final Map<UUID, Long> lastSummonMs = new ConcurrentHashMap<>();

    public void recordSummon(UUID playerId) {
        lastSummonMs.put(playerId, System.currentTimeMillis());
    }

    /** Returns 0 if no cooldown is active (or config is disabled). */
    public long remainingMs(UUID playerId, long cooldownMs) {
        if (cooldownMs <= 0L) return 0L;
        Long last = lastSummonMs.get(playerId);
        if (last == null) return 0L;
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0L, cooldownMs - elapsed);
    }

    public void clear() {
        lastSummonMs.clear();
    }

    private GlobalSummonCooldownTracker() {}
}
