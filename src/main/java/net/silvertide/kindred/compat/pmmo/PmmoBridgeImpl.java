package net.silvertide.kindred.compat.pmmo;

import harmonised.pmmo.api.APIUtils;
import net.minecraft.world.entity.player.Player;

/**
 * Direct PMMO API caller. Only instantiated by {@link PmmoCompat} via
 * {@code Class.forName}, so its imports of {@code harmonised.pmmo.*} never get
 * resolved when PMMO isn't on the classpath.
 *
 * <p>Do not reference this class anywhere else — touching it from a path that
 * runs without PMMO will trigger {@code NoClassDefFoundError}.</p>
 */
public final class PmmoBridgeImpl implements PmmoBridge {
    @Override
    public long getSkillLevel(Player player, String skill) {
        return APIUtils.getLevel(skill, player);
    }
}
