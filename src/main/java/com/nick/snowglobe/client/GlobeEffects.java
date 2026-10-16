package com.nick.snowglobe.client;

import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSpace;
import com.nick.snowglobe.GlobeView;
import com.nick.snowglobe.SnowGlobeMod;
import com.nick.snowglobe.net.GlobeNetwork.GlobeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
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
                }
            }
            case GlobeEvent.KNOCKED ->
            {
                // Someone huge knocking on the glass.
                rumble(10, 1.8f, false);
                play(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 2.0f, 0.5f);
                play(SoundEvents.WOOD_HIT, 1.5f, 0.5f);
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
                default -> ParticleTypes.SNOWFLAKE;
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
        float flash = phase(flashStart, 6, partial);
        if (flash >= 0)
            alpha = Math.max(alpha, 0.75f * (1 - flash));
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
