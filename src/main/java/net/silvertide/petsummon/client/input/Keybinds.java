package net.silvertide.petsummon.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

public final class Keybinds {
    public static final String CATEGORY = "key.categories.petsummon";

    public static final KeyMapping SUMMON_ACTIVE_PET = new KeyMapping(
            "key.petsummon.summon_active_pet",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY
    );

    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            "key.petsummon.open_roster",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY
    );

    private Keybinds() {}
}
