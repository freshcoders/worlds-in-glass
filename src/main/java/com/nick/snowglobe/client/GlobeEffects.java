package com.nick.snowglobe.client;

import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSpace;
import com.nick.snowglobe.GlobeView;
import com.nick.snowglobe.SnowGlobeMod;
import com.nick.snowglobe.net.GlobeNetwork.GlobeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * What you feel of globes on this client: the zoom and white-out when going in or out, the rumble when someone
 * shakes or knocks on the globe you're in, and the weather a shake stirs up around you.
 */
@EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT)
public final class GlobeEffects
{
    private static final int ARRIVE_TICKS = 16;
    private static final int STIR_TICKS = 50;

    /** Counts client ticks; all the timings below are in it. */
    private static long ticks;
    private static int diving;        // 1 going in, -1 going out, 0 not
    private static long diveStart = Long.MIN_VALUE;
    private static int arriving;      // as above, for opening up again on arrival
    private static long arriveStart = Long.MIN_VALUE;
    private static long rumbleStart = Long.MIN_VALUE;
    private static int rumbleTicks;
    private static float rumbleStrength;
    private static boolean rumbleSways;
    private static long stirStart = Long.MIN_VALUE;
    private static GlobeKind stirKind = GlobeKind.SNOW_GLOBE;
    private static long flashStart = Long.MIN_VALUE;
    /** The last strike thundered inside, so each strike is heard once. */
    private static long lastStrike = Long.MIN_VALUE;

    private static final ParticleOptions SAND_DUST = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST,
            net.minecraft.world.level.block.Blocks.SAND.defaultBlockState());

    private GlobeEffects() {}

    static void onEvent(int event, int kindOrdinal)
    {
        GlobeKind kind = GlobeKind.values()[Mth.clamp(kindOrdinal, 0, GlobeKind.values().length - 1)];
        switch (event)
        {
            case GlobeEvent.SHAKEN ->
            {
                rumble(40, 1, kind == GlobeKind.SHIP_BOTTLE);
                stirStart = ticks;
                stirKind = kind;
                // Inside, the same sounds as outside, but deep: the whole world is being shaken.
                switch (kind)
                {
                    case SNOW_GLOBE -> play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.6f);
                    case STORM_GLOBE ->
                    {
                        flashStart = ticks;
                        play(SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0f, 0.9f);
                    }
                    case TERRARIUM ->
                    {
                        play(SoundEvents.GLASS_HIT, 1.2f, 0.5f);
                        play(SoundEvents.AZALEA_LEAVES_STEP, 1.5f, 0.6f);
                    }
                    case SHIP_BOTTLE ->
                    {
                        play(SoundEvents.GLASS_HIT, 1.2f, 0.6f);
                        play(SoundEvents.BUCKET_EMPTY, 1.0f, 0.7f);
                    }
                    case DESERT_GLOBE ->
                    {
                        play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.55f);
                        play(SoundEvents.SAND_BREAK, 1.5f, 0.5f);
                    }
                    case AQUARIUM ->
                    {
                        play(SoundEvents.GLASS_HIT, 1.2f, 0.5f);
                        play(SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1.5f, 0.6f);
                    }
                    case STAR_GLOBE ->
                    {
                        play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 0.9f);
                        play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0f, 0.8f);
                    }
                    case NETHER_JAR ->
                    {
                        play(SoundEvents.GLASS_HIT, 1.2f, 0.5f);
                        play(SoundEvents.LAVA_POP, 1.5f, 0.6f);
                    }
                }
            }
            case GlobeEvent.WOUND -> MusicBoxes.woundInside(kind);
            case GlobeEvent.KNOCKED ->
            {
                // Someone huge knocking on the glass.
                rumble(10, 1.8f, false);
                play(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 2.0f, 0.5f);
                play(SoundEvents.WOOD_HIT, 1.5f, 0.5f);
                // A knock on a storm globe sets the storm off.
                if (kind == GlobeKind.STORM_GLOBE)
                {
                    flashStart = ticks;
                    play(SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0f, 0.9f);
                }
            }
            case GlobeEvent.DIVE_IN, GlobeEvent.DIVE_OUT ->
            {
                diving = event == GlobeEvent.DIVE_IN ? 1 : -1;
                diveStart = ticks;
                play(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.8f, diving > 0 ? 1.6f : 0.8f);
            }
            case GlobeEvent.ARRIVED ->
            {
                arriving = diving != 0 ? diving : 1;
                arriveStart = ticks;
                diving = 0;
            }
            default -> {}
        }
    }

    private static void rumble(int duration, float strength, boolean sways)
    {
        rumbleStart = ticks;
        rumbleTicks = duration;
        rumbleStrength = strength;
        rumbleSways = sways;
    }

    private static void play(SoundEvent sound, float volume, float pitch)
    {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null)
            player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), sound, SoundSource.AMBIENT, volume, pitch, false);
    }

    /** 0 to 1 over a span of ticks since {@code start}, or -1 if outside it. */
    private static float phase(long start, int span, float partialTick)
    {
        if (start == Long.MIN_VALUE)
            return -1;
        float t = (ticks - start + partialTick) / span;
        return t >= 0 && t <= 1 ? t : -1;
    }

    private static float smooth(float t)
    {
        return t * t * (3 - 2 * t);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        ticks++;
        // A dive that never arrived (refused, or the server moved on) shouldn't leave the screen white.
        if (diving != 0 && ticks - diveStart > GlobeView.DIVE_TICKS + 40)
        {
            arriving = diving;
            arriveStart = ticks;
            diving = 0;
        }
        stir();
        storm();
        weather();
    }

    /** How bright a lightning flash is right now (0 to 1), for the sky and the white-out. */
    static float flash(float partial)
    {
        float t = phase(flashStart, 6, partial);
        return t >= 0 ? 1 - t : 0;
    }

    private static final ParticleOptions[] AUTUMN_LEAVES = {
            new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, net.minecraft.world.level.block.Blocks.ORANGE_TERRACOTTA.defaultBlockState()),
            new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, net.minecraft.world.level.block.Blocks.RED_TERRACOTTA.defaultBlockState()),
            new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, net.minecraft.world.level.block.Blocks.YELLOW_TERRACOTTA.defaultBlockState()),
            new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, net.minecraft.world.level.block.Blocks.BROWN_TERRACOTTA.defaultBlockState())};
    private static final ParticleOptions BLOWN_SAND = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.86f, 0.76f, 0.52f), 0.9f);

    /** What a snow globe's season sends drifting down: snow, blossom, fireflies or leaves. */
    private static ParticleOptions seasonParticle(RandomSource random)
    {
        return switch (OutsideView.insideSeason())
        {
            case com.nick.snowglobe.GlobeSavedData.SPRING -> ParticleTypes.CHERRY_LEAVES;
            case com.nick.snowglobe.GlobeSavedData.SUMMER -> ParticleTypes.GLOW;
            case com.nick.snowglobe.GlobeSavedData.AUTUMN -> AUTUMN_LEAVES[random.nextInt(AUTUMN_LEAVES.length)];
            default -> ParticleTypes.SNOWFLAKE;
        };
    }

    /**
     * The weather of the globe you're in, around you all the time: the snow globe's season, blown sand in the desert,
     * ash and embers in the nether jar, spores in the terrarium, bubbles underwater in the aquarium.
     */
    private static void weather()
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        GlobeKind kind = OutsideView.insideKind();
        if (level == null || player == null || kind == null || mc.isPaused())
            return;
        RandomSource random = level.random;
        double amount = com.nick.snowglobe.SnowGlobeConfig.weatherInside();
        float rate = switch (kind)
        {
            case SNOW_GLOBE -> OutsideView.insideSeason() == com.nick.snowglobe.GlobeSavedData.SUMMER ? 0.6f : 5;
            case DESERT_GLOBE -> 4;
            case NETHER_JAR -> 3;
            case TERRARIUM -> 1;
            case AQUARIUM -> player.isUnderWater() ? 2 : 0;
            default -> 0;
        };
        rate *= (float) amount;
        int count = (int) rate + (random.nextFloat() < rate - (int) rate ? 1 : 0);
        for (int i = 0; i < count; i++)
        {
            double x = player.getX() + (random.nextDouble() - 0.5) * 24, z = player.getZ() + (random.nextDouble() - 0.5) * 24;
            double y = player.getY() + random.nextDouble() * 10;
            switch (kind)
            {
                case SNOW_GLOBE ->
                {
                    ParticleOptions particle = seasonParticle(random);
                    double fall = particle == ParticleTypes.GLOW ? 0.01 : -0.04;
                    level.addParticle(particle, x, particle == ParticleTypes.GLOW ? player.getY() + random.nextDouble() * 3 : y + 3,
                            z, (random.nextDouble() - 0.5) * 0.04, fall, (random.nextDouble() - 0.5) * 0.04);
                }
                // A steady wind from the east, low over the dunes.
                case DESERT_GLOBE -> level.addParticle(BLOWN_SAND, x, player.getY() + random.nextDouble() * 3, z, -1.5, 0.02, 0.2);
                case NETHER_JAR ->
                {
                    level.addParticle(random.nextInt(4) == 0 ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH, x, y, z, 0, 0, 0);
                    if (random.nextInt(25) == 0)
                        level.addParticle(ParticleTypes.LAVA, x, player.getY(), z, 0, 0, 0);
                }
                case TERRARIUM -> level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 0, 0, 0);
                case AQUARIUM -> level.addParticle(ParticleTypes.BUBBLE, x, player.getY() - 2 + random.nextDouble() * 3, z, 0, 0.05, 0);
                default -> {}
            }
        }
        if (kind == GlobeKind.NETHER_JAR && random.nextInt(240) == 0)
            play(SoundEvents.AMBIENT_BASALT_DELTAS_ADDITIONS.value(), 0.8f, 0.8f);
        if (kind == GlobeKind.DESERT_GLOBE && random.nextInt(300) == 0)
            play(SoundEvents.SAND_STEP, 0.5f, 0.6f);
    }

    /**
     * Inside a storm globe it's always storming: rain all round you, and lightning in step with the miniature
     * outside (the same strikes), each with a flash and a deep, full-size thunderclap.
     */
    private static void storm()
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || OutsideView.insideKind() != GlobeKind.STORM_GLOBE || mc.isPaused())
            return;
        BlockPos globePos = OutsideView.insideGlobePos();
        long tick = level.getGameTime() % 240000;
        long start = GlobeRenderer.strikeStart(tick, globePos.asLong());
        if (start != Long.MIN_VALUE && start == tick && lastStrike != start)
        {
            lastStrike = start;
            flashStart = ticks;
            play(SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0f, 0.9f);
            play(SoundEvents.LIGHTNING_BOLT_IMPACT, 0.6f, 0.8f);
        }
        RandomSource random = level.random;
        int drops = (int) Math.round(12 * com.nick.snowglobe.SnowGlobeConfig.weatherInside());
        for (int i = 0; i < drops; i++)
        {
            double x = player.getX() + (random.nextDouble() - 0.5) * 24, z = player.getZ() + (random.nextDouble() - 0.5) * 24;
            double y = player.getY() + 4 + random.nextDouble() * 8;
            level.addParticle(ParticleTypes.FALLING_WATER, x, y, z, 0, -0.3, 0);
        }
        if (random.nextInt(20) == 0)
            play(SoundEvents.WEATHER_RAIN, 0.25f, 0.9f + random.nextFloat() * 0.2f);
    }

    /** A shake stirs up the globe's weather around you, if you're inside. */
    private static void stir()
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        float t = phase(stirStart, STIR_TICKS, 0);
        if (t < 0 || level == null || player == null || level.dimension() != GlobeSpace.LEVEL)
            return;
        RandomSource random = level.random;
        int count = (int) (24 * (1 - t));
        for (int i = 0; i < count; i++)
        {
            double angle = random.nextDouble() * Math.PI * 2, dist = 1 + random.nextDouble() * 9;
            double x = player.getX() + Math.cos(angle) * dist, z = player.getZ() + Math.sin(angle) * dist;
            double y = player.getY() + random.nextDouble() * 8 - 1;
            // Whirling round you, as if the whole world had been tipped over and back.
            double swirl = 0.25 * (1 - t);
            double vx = -Math.sin(angle) * swirl, vz = Math.cos(angle) * swirl;
            ParticleOptions particle = switch (stirKind)
            {
                case STORM_GLOBE -> ParticleTypes.FALLING_WATER;
                case TERRARIUM -> random.nextBoolean() ? ParticleTypes.FALLING_SPORE_BLOSSOM : ParticleTypes.SPORE_BLOSSOM_AIR;
                case SHIP_BOTTLE -> random.nextBoolean() ? ParticleTypes.SPLASH : ParticleTypes.BUBBLE_POP;
                case DESERT_GLOBE -> SAND_DUST;
                case AQUARIUM -> player.isUnderWater() ? ParticleTypes.BUBBLE : ParticleTypes.BUBBLE_POP;
                case STAR_GLOBE -> ParticleTypes.END_ROD;
                case NETHER_JAR -> random.nextInt(4) == 0 ? ParticleTypes.LAVA : ParticleTypes.ASH;
                default -> seasonParticle(random);
            };
            level.addParticle(particle, x, y, z, vx, -0.05 - random.nextDouble() * 0.05, vz);
        }
    }

    /** Zooms in while diving into a globe, out while leaving, and eases back to normal on arrival. */
    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event)
    {
        float partial = (float) event.getPartialTick();
        double factor = 1;
        float dive = diving != 0 ? Mth.clamp((ticks - diveStart + partial) / GlobeView.DIVE_TICKS, 0, 1) : -1;
        if (dive >= 0)
            factor = diving > 0 ? 1 - 0.75 * smooth(dive) : 1 + 0.5 * smooth(dive);
        float arrive = phase(arriveStart, ARRIVE_TICKS, partial);
        if (arrive >= 0)
            factor = Mth.lerp(smooth(arrive), arriving > 0 ? 0.45 : 1.6, 1);
        if (factor != 1)
            event.setFOV(event.getFOV() * factor);
    }

    /** The rumble of a shake or a knock, felt inside the globe. */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event)
    {
        float t = phase(rumbleStart, Math.max(1, rumbleTicks), (float) event.getPartialTick());
        if (t < 0)
            return;
        double time = ticks + event.getPartialTick();
        float amp = rumbleStrength * (1 - t) * (1 - t);
        if (rumbleSways)
        {
            // In a bottle, a shake rolls you like a wave.
            event.setRoll(event.getRoll() + (float) Math.sin(time * 0.35) * 9 * amp);
            event.setPitch(event.getPitch() + (float) Math.sin(time * 0.27) * 3 * amp);
        }
        else
        {
            event.setRoll(event.getRoll() + (float) Math.sin(time * 2.3) * 3 * amp);
            event.setPitch(event.getPitch() + (float) Math.sin(time * 3.1) * 1.5f * amp);
            event.setYaw(event.getYaw() + (float) Math.cos(time * 2.7) * 1.5f * amp);
        }
    }

    /** How white the screen is right now: fading out during a dive, back in on arrival, and lightning flashes. */
    private static float whiteness(float partial)
    {
        float alpha = 0;
        if (diving != 0)
            alpha = smooth(Mth.clamp(((ticks - diveStart + partial) / GlobeView.DIVE_TICKS - 0.35f) / 0.65f, 0, 1));
        float arrive = phase(arriveStart, ARRIVE_TICKS, partial);
        if (arrive >= 0)
            alpha = Math.max(alpha, 1 - smooth(arrive));
        alpha = Math.max(alpha, 0.75f * flash(partial));
        return alpha;
    }

    @EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Layers
    {
        private Layers() {}

        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event)
        {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(SnowGlobeMod.MODID, "white_out"), (graphics, deltaTracker) -> {
                float alpha = whiteness(deltaTracker.getGameTimeDeltaPartialTick(false));
                if (alpha > 0.01f)
                    graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ((int) (alpha * 255) << 24) | 0xEAF4FF);
            });
        }
    }
}
