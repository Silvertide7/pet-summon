package net.silvertide.kindred.compat.pmmo;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import net.silvertide.kindred.Kindred;

/**
 * Public entry point for PMMO interaction. Contains zero direct PMMO references
 * so it's always loadable. The {@link PmmoBridge} implementation is resolved at
 * static-init time via {@link Class#forName}; if PMMO isn't installed the
 * bridge stays null and every call short-circuits.
 *
 * <p>Always check {@link #isAvailable()} before reading levels — gameplay code
 * should treat unavailable as "PMMO is off, ignore the gate," not as "level 0."</p>
 */
public final class PmmoCompat {
    private static final PmmoBridge BRIDGE;

    static {
        PmmoBridge bridge = null;
        if (ModList.get().isLoaded("pmmo")) {
            try {
                bridge = (PmmoBridge) Class.forName("net.silvertide.kindred.compat.pmmo.PmmoBridgeImpl")
                        .getDeclaredConstructor()
                        .newInstance();
                Kindred.LOGGER.info("[kindred] PMMO compat bridge initialized.");
            } catch (Throwable t) {
                Kindred.LOGGER.error("[kindred] Failed to initialize PMMO compat bridge", t);
            }
        }
        BRIDGE = bridge;
    }

    public static boolean isAvailable() {
        return BRIDGE != null;
    }

    public static long getSkillLevel(Player player, String skill) {
        return BRIDGE != null ? BRIDGE.getSkillLevel(player, skill) : 0L;
    }

    private PmmoCompat() {}
}
