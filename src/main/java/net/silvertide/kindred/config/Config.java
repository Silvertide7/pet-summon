package net.silvertide.kindred.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.silvertide.kindred.compat.pmmo.PmmoMode;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Each group is bracketed by static initializer blocks calling push/pop on the
    // builder. Java executes static blocks and static-field initializers in textual
    // order (JLS 12.4.2), so the field initializers below land inside the active
    // category. Entries after the first in each section get a leading "" comment
    // line to give the generated TOML a bare "#" between configs — the closest we
    // can get to visual separation without writing the TOML by hand.

    // ───── Bonding & roster ─────
    static { BUILDER.push("bonding"); }

    public static final ModConfigSpec.IntValue MAX_BONDS = BUILDER
            .comment("Maximum number of bonds per player.")
            .defineInRange("maxBonds", 10, 1, 64);

    public static final ModConfigSpec.BooleanValue REQUIRE_SADDLEABLE = BUILDER
            .comment("",
                     "If true, only entities implementing Saddleable can be bonded (mount-only mode).")
            .define("requireSaddleable", false);

    public static final ModConfigSpec.IntValue BOND_XP_LEVEL_COST = BUILDER
            .comment("",
                     "XP levels consumed per successful bond claim. 0 disables (default). " +
                     "Acts as a balance lever: makes bonding feel earned. The screen previews " +
                     "the cost above the Bind button and refuses the bind if the player's level " +
                     "is below the cost.")
            .defineInRange("bondXpLevelCost", 0, 0, 1000);

    static { BUILDER.pop(); }

    // ───── Summoning behavior ─────
    static { BUILDER.push("summoning"); }

    public static final ModConfigSpec.DoubleValue WALK_RANGE = BUILDER
            .comment("If a summoned pet is within this distance (blocks) and in the same dimension, it walks instead of teleporting.")
            .defineInRange("walkRange", 30.0D, 0.0D, 256.0D);

    public static final ModConfigSpec.DoubleValue WALK_SPEED = BUILDER
            .comment("",
                     "Pathfinding speed multiplier when walking to the player.")
            .defineInRange("walkSpeed", 1.8D, 0.1D, 8.0D);

    public static final ModConfigSpec.BooleanValue CROSS_DIM_ALLOWED = BUILDER
            .comment("",
                     "Allow summoning a pet from another dimension.")
            .define("crossDimAllowed", true);

    public static final ModConfigSpec.BooleanValue REQUIRE_SPACE = BUILDER
            .comment("",
                     "If true, refuse to summon when the 3x3x3 space around the player is obstructed.")
            .define("requireSpace", true);

    static { BUILDER.pop(); }

    // ───── Cooldowns ─────
    static { BUILDER.push("cooldowns"); }

    public static final ModConfigSpec.IntValue SUMMON_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown between summons of the same bond, in ticks (20 = 1 second).")
            .defineInRange("summonCooldownTicks", 100, 0, 72000);

    public static final ModConfigSpec.IntValue SUMMON_GLOBAL_COOLDOWN_SECONDS = BUILDER
            .comment("",
                     "Per-player cooldown (in seconds) between any two summons regardless of which bond. " +
                     "0 disables. Distinct from summonCooldownTicks which only blocks summoning the same pet repeatedly.")
            .defineInRange("summonGlobalCooldownSeconds", 10, 0, 86400);

    static { BUILDER.pop(); }

    // ───── Death & revival ─────
    static { BUILDER.push("death"); }

    public static final ModConfigSpec.BooleanValue DEATH_IS_PERMANENT = BUILDER
            .comment("If true, a bonded pet's death breaks the bond. If false, summoning a dead pet respawns it.")
            .define("deathIsPermanent", false);

    public static final ModConfigSpec.BooleanValue DROP_LOOT_ON_DEATH = BUILDER
            .comment("",
                     "If true (vanilla), bonded pets drop their inventory and loot on death. " +
                     "Default is false to pair with deathIsPermanent=false (the default): if the " +
                     "pet is going to be resummoned, scattering its saddle/armor/chest contents " +
                     "across the death site is just an item-recovery chore. Set to true if you " +
                     "want vanilla drop behavior, e.g. when running deathIsPermanent=true. " +
                     "Note: ignored when deathIsPermanent=true — the bond is stripped before " +
                     "the drops event fires, so vanilla drops always happen in that mode.")
            .define("dropLootOnDeath", false);

    public static final ModConfigSpec.IntValue REVIVAL_COOLDOWN_SECONDS = BUILDER
            .comment("",
                     "Per-bond cooldown (in seconds) after a non-permanent death before the bond can be summoned again. " +
                     "0 disables. Adds weight to deaths without going full permadeath. Has no effect when deathIsPermanent=true.")
            .defineInRange("revivalCooldownSeconds", 0, 0, 86400);

    static { BUILDER.pop(); }

    // ───── Input (hold-to-confirm) ─────
    static { BUILDER.push("input"); }

    public static final ModConfigSpec.DoubleValue HOLD_TO_SUMMON_SECONDS = BUILDER
            .comment("Seconds to hold the summon keybind (or screen Summon button) to confirm summoning.")
            .defineInRange("holdToSummonSeconds", 1.0D, 0.1D, 10.0D);

    public static final ModConfigSpec.DoubleValue HOLD_TO_DISMISS_SECONDS = BUILDER
            .comment("",
                     "Seconds to hold the summon keybind (or screen Dismiss button) to confirm dismissing the active pet.")
            .defineInRange("holdToDismissSeconds", 1.0D, 0.1D, 10.0D);

    public static final ModConfigSpec.BooleanValue CANCEL_HOLD_ON_DAMAGE = BUILDER
            .comment("",
                     "If true, taking damage cancels any in-progress summon/dismiss hold (mirrors vanilla bow-draw / eating interrupt).")
            .define("cancelHoldOnDamage", true);

    static { BUILDER.pop(); }

    // ───── PMMO compat (Project MMO) ─────
    static { BUILDER.push("pmmo"); }

    public static final ModConfigSpec.BooleanValue PMMO_ENABLED = BUILDER
            .comment("Master toggle for PMMO integration. No-op if PMMO isn't loaded.")
            .define("pmmoEnabled", false);

    public static final ModConfigSpec.ConfigValue<String> PMMO_SKILL = BUILDER
            .comment("",
                     "PMMO skill ID gating bond claims. Used both as the API key " +
                     "(\"charisma\" → APIUtils.getLevel(\"charisma\", player)) and " +
                     "as the lang-key suffix (pmmo.charisma → display name resolved " +
                     "via PMMO's en_us.json).")
            .define("pmmoSkill", "charisma");

    public static final ModConfigSpec.EnumValue<PmmoMode> PMMO_MODE = BUILDER
            .comment("",
                     "ALL_OR_NOTHING: at pmmoStartLevel, all bonds up to maxBonds unlock at once.",
                     "LINEAR: 1 bond at pmmoStartLevel; +1 every pmmoIncrementPerBond levels above, capped at maxBonds.")
            .defineEnum("pmmoMode", PmmoMode.ALL_OR_NOTHING);

    public static final ModConfigSpec.IntValue PMMO_START_LEVEL = BUILDER
            .comment("",
                     "Skill level required for the first bond.")
            .defineInRange("pmmoStartLevel", 3, 0, 1000);

    public static final ModConfigSpec.IntValue PMMO_INCREMENT_PER_BOND = BUILDER
            .comment("",
                     "Levels per additional bond in LINEAR mode. Ignored in ALL_OR_NOTHING.")
            .defineInRange("pmmoIncrementPerBond", 2, 1, 1000);

    static { BUILDER.pop(); }

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ───── ms helpers ─────
    // Internal code wants milliseconds for time math; configs are in seconds for users.

    public static long holdToDismissMs() {
        return Math.round(HOLD_TO_DISMISS_SECONDS.get() * 1000.0D);
    }

    public static long holdToSummonMs() {
        return Math.round(HOLD_TO_SUMMON_SECONDS.get() * 1000.0D);
    }

    public static long summonGlobalCooldownMs() {
        return SUMMON_GLOBAL_COOLDOWN_SECONDS.get() * 1000L;
    }

    public static long revivalCooldownMs() {
        return REVIVAL_COOLDOWN_SECONDS.get() * 1000L;
    }

    private Config() {}
}
