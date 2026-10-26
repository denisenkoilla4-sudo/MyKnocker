package com.myknocker.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server-side behaviour settings. File: config/myknocker-common.toml */
public final class KnockerConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLE_STALKING;
    public static final ForgeConfigSpec.BooleanValue ENABLE_KNOCKING;
    public static final ForgeConfigSpec.BooleanValue ENABLE_SLEEP_VISIT;
    public static final ForgeConfigSpec.BooleanValue ENABLE_JUMPSCARE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_ATTACKS;

    public static final ForgeConfigSpec.DoubleValue EVENT_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DAY_CHANCE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue ATTACK_CHANCE;
    public static final ForgeConfigSpec.DoubleValue SLEEP_VISIT_CHANCE;

    public static final ForgeConfigSpec.IntValue MIN_COOLDOWN_MINUTES;
    public static final ForgeConfigSpec.IntValue MAX_COOLDOWN_MINUTES;
    public static final ForgeConfigSpec.IntValue MAX_LIFETIME_SECONDS;
    public static final ForgeConfigSpec.IntValue HUNT_SECONDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("events");
        ENABLE_STALKING = b.comment("It appears in the distance and slowly follows you.")
                .define("enableStalking", true);
        ENABLE_KNOCKING = b.comment("At night near your bed/respawn point it knocks on your doors and walls.")
                .define("enableKnocking", true);
        ENABLE_SLEEP_VISIT = b.comment("It can stand next to your bed while you sleep.")
                .define("enableSleepVisit", true);
        ENABLE_JUMPSCARE = b.comment("Full-screen jumpscare with sound.")
                .define("enableJumpscare", true);
        ENABLE_ATTACKS = b.comment("It can chase and hit players (and fights back when hit).")
                .define("enableAttacks", true);
        b.pop();

        b.push("chances");
        EVENT_CHANCE = b.comment("Chance to start an event, checked every 10 seconds per player once the cooldown is over.")
                .defineInRange("eventChance", 0.15D, 0.0D, 1.0D);
        DAY_CHANCE_MULTIPLIER = b.comment("Multiplier of eventChance during the day.")
                .defineInRange("dayChanceMultiplier", 0.25D, 0.0D, 1.0D);
        ATTACK_CHANCE = b.comment("Chance that an event ends with the Knocker attacking.")
                .defineInRange("attackChance", 0.25D, 0.0D, 1.0D);
        SLEEP_VISIT_CHANCE = b.comment("Chance per sleep that it visits your bed (also limited by a 1-2 day cooldown).")
                .defineInRange("sleepVisitChance", 0.2D, 0.0D, 1.0D);
        b.pop();

        b.push("timing");
        MIN_COOLDOWN_MINUTES = b.comment("Minimum real-time minutes between events for one player.")
                .defineInRange("minCooldownMinutes", 8, 0, 600);
        MAX_COOLDOWN_MINUTES = b.comment("Maximum real-time minutes between events for one player.")
                .defineInRange("maxCooldownMinutes", 20, 0, 600);
        MAX_LIFETIME_SECONDS = b.comment("It disappears on its own after this many seconds (unless it is hunting).")
                .defineInRange("maxLifetimeSeconds", 240, 30, 3600);
        HUNT_SECONDS = b.comment("How long a chase lasts.")
                .defineInRange("huntSeconds", 40, 5, 600);
        b.pop();

        SPEC = b.build();
    }

    private KnockerConfig() {
    }
}
