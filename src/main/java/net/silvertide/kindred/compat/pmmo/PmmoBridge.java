package net.silvertide.kindred.compat.pmmo;

import net.minecraft.world.entity.player.Player;

/**
 * Indirection between {@link PmmoCompat} (always loaded, no PMMO classes) and
 * {@link PmmoBridgeImpl} (PMMO classes referenced directly, only loaded when PMMO
 * is present). Lets us call PMMO without forcing the JVM to resolve PMMO classes
 * in setups where the mod isn't installed.
 */
public interface PmmoBridge {
    /** Returns the player's current level in {@code skill}, or 0 if the skill is
     *  unknown to PMMO. */
    long getSkillLevel(Player player, String skill);
}
