package net.silvertide.kindred.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

public final class Keybinds {
    public static final String CATEGORY = "key.categories.kindred";

    public static final KeyMapping SUMMON_ACTIVE_PET = new KeyMapping(
            "key.kindred.summon_active_pet",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_V,
            CATEGORY
    );

    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            "key.kindred.open_roster",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_G,
            CATEGORY
    );

    private Keybinds() {}
}
