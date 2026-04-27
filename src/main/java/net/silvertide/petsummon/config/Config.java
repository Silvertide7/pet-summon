package net.silvertide.petsummon.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_BONDS = BUILDER
            .comment("Maximum number of bonds per player.")
            .defineInRange("maxBonds", 5, 1, 64);

    public static final ModConfigSpec.BooleanValue REQUIRE_SADDLEABLE = BUILDER
            .comment("If true, only entities implementing Saddleable can be bonded (mount-only mode).")
            .define("requireSaddleable", false);

    public static final ModConfigSpec.DoubleValue WALK_RANGE = BUILDER
            .comment("If a summoned pet is within this distance (blocks) and in the same dimension, it walks instead of teleporting.")
            .defineInRange("walkRange", 30.0D, 0.0D, 256.0D);

    public static final ModConfigSpec.DoubleValue WALK_SPEED = BUILDER
            .comment("Pathfinding speed multiplier when walking to the player.")
            .defineInRange("walkSpeed", 1.8D, 0.1D, 8.0D);

    public static final ModConfigSpec.IntValue SUMMON_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown between summons of the same bond, in ticks (20 = 1 second).")
            .defineInRange("summonCooldownTicks", 100, 0, 72000);

    public static final ModConfigSpec.BooleanValue CROSS_DIM_ALLOWED = BUILDER
            .comment("Allow summoning a pet from another dimension.")
            .define("crossDimAllowed", true);

    public static final ModConfigSpec.IntValue CLAIM_WINDOW_SECONDS = BUILDER
            .comment("Seconds the player has to right-click an eligible pet after arming a claim from the screen.")
            .defineInRange("claimWindowSeconds", 30, 1, 600);

    public static final ModConfigSpec.BooleanValue DEATH_IS_PERMANENT = BUILDER
            .comment("If true, a bonded pet's death breaks the bond. If false, summoning a dead pet respawns it.")
            .define("deathIsPermanent", false);

    public static final ModConfigSpec.BooleanValue AUTO_MOUNT = BUILDER
            .comment("If true, the player is auto-seated on a saddleable bond on summon.")
            .define("autoMount", false);

    public static final ModConfigSpec.BooleanValue REQUIRE_SPACE = BUILDER
            .comment("If true, refuse to summon when the 3x3x3 space around the player is obstructed.")
            .define("requireSpace", true);

    public static final ModConfigSpec.IntValue HOLD_TO_DISMISS_MS = BUILDER
            .comment("Milliseconds to hold the summon keybind to confirm dismissing the active pet (when within 6 blocks).")
            .defineInRange("holdToDismissMs", 1000, 100, 10000);

    public static final ModConfigSpec.IntValue HOLD_TO_SUMMON_MS = BUILDER
            .comment("Milliseconds to hold the summon keybind to confirm summoning (when no active pet is nearby).")
            .defineInRange("holdToSummonMs", 1000, 100, 10000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
