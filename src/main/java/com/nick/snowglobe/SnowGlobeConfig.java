package com.nick.snowglobe;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings each player can change for themselves (Mods > Worlds in Glass > Config). Only affects what they see and hear. */
public final class SnowGlobeConfig
{
    public static final ModConfigSpec SPEC;
    /** One in this many possible lightning slots strikes (0: no lightning). */
    public static final ModConfigSpec.IntValue LIGHTNING_RARITY;
    /** How much weather falls around you inside a globe, 0 to 2. */
    public static final ModConfigSpec.DoubleValue WEATHER_INSIDE;
    /** Whether a storm globe's tiny thunder can be heard from outside. */
    public static final ModConfigSpec.BooleanValue THUNDER_OUTSIDE;

    static
    {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        LIGHTNING_RARITY = builder
                .comment("How rare lightning is in storm globes: one in this many chances strikes (a chance every 2.5 seconds). 0 turns it off.")
                .translation("snowglobe.config.lightning_rarity")
                .defineInRange("lightningRarity", 3, 0, 10);
        WEATHER_INSIDE = builder
                .comment("How much rain, snow, sand or ash falls around you inside a globe. 0 turns it off.")
                .translation("snowglobe.config.weather_inside")
                .defineInRange("weatherInside", 1.0, 0.0, 2.0);
        THUNDER_OUTSIDE = builder
                .comment("Whether you hear a storm globe's tiny thunder when standing near it.")
                .translation("snowglobe.config.thunder_outside")
                .define("thunderOutside", true);
        SPEC = builder.build();
    }

    private SnowGlobeConfig() {}

    /** The values, safe to read before the config file has loaded (then the defaults). */
    public static int lightningRarity()
    {
        return SPEC.isLoaded() ? LIGHTNING_RARITY.get() : 3;
    }

    public static double weatherInside()
    {
        return SPEC.isLoaded() ? WEATHER_INSIDE.get() : 1.0;
    }

    public static boolean thunderOutside()
    {
        return !SPEC.isLoaded() || THUNDER_OUTSIDE.get();
    }
}
