package com.nick.worldsinglass.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nick.worldsinglass.GlobeKind;
import com.nick.worldsinglass.GlobeSavedData;
import com.nick.worldsinglass.GlobeSpace;
import com.nick.worldsinglass.WorldsInGlassMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;

/**
 * The sky inside globes: each kind of globe has its own. Sunlit blue over the snow, a sunset over the desert,
 * night with the northern lights in the star globe, storm grey, underwater teal, or the nether's red. The light
 * inside follows it, so the star globe really is dark.
 */
public final class GlobeSky extends DimensionSpecialEffects
{
    private static final int LAT = 12, LON = 24;
    private static final float RADIUS = 100;
    private static final float[][] STARS = new float[260][];

    static
    {
        Random random = new Random(1977);
        for (int i = 0; i < STARS.length; i++)
        {
            // Spread evenly over the upper sky: x, y, z on the unit sphere, size, twinkle phase.
            double y = 0.04 + random.nextDouble() * 0.96, angle = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - y * y);
            STARS[i] = new float[] {(float) (Math.cos(angle) * r), (float) y, (float) (Math.sin(angle) * r),
                    0.25f + random.nextFloat() * 0.45f, random.nextFloat() * 6.28f};
        }
    }

    GlobeSky()
    {
        super(Float.NaN, true, SkyType.NONE, false, false);
    }

    /** The sky of the globe you're in now: its kind and time of day. */
    private record Look(GlobeKind kind, int phase) {}

    private static Look look()
    {
        GlobeKind kind = OutsideView.insideKind();
        return new Look(kind != null ? kind : GlobeKind.SNOW_GLOBE, OutsideView.insidePhase());
    }

    /** Sky colours, top and horizon, as 0-1 floats. */
    private static float[][] colours(Look look)
    {
        return switch (look.kind())
        {
            case STAR_GLOBE -> new float[][] {{0.02f, 0.03f, 0.10f}, {0.06f, 0.09f, 0.22f}};
            case NETHER_JAR -> new float[][] {{0.22f, 0.03f, 0.02f}, {0.58f, 0.16f, 0.06f}};
            case STORM_GLOBE -> new float[][] {{0.23f, 0.25f, 0.29f}, {0.38f, 0.40f, 0.44f}};
            case AQUARIUM -> new float[][] {{0.05f, 0.25f, 0.42f}, {0.12f, 0.45f, 0.58f}};
            default -> switch (look.phase())
            {
                case GlobeSavedData.PHASE_SUNSET -> new float[][] {{0.24f, 0.25f, 0.55f}, {1.0f, 0.55f, 0.28f}};
                case GlobeSavedData.PHASE_NIGHT -> new float[][] {{0.02f, 0.03f, 0.09f}, {0.07f, 0.09f, 0.20f}};
                case GlobeSavedData.PHASE_SUNRISE -> new float[][] {{0.40f, 0.52f, 0.85f}, {1.0f, 0.74f, 0.55f}};
                default -> new float[][] {{0.45f, 0.65f, 1.0f}, {0.74f, 0.86f, 1.0f}};
            };
        };
    }

    /** How dark it is inside: 1 in full day, down to about a quarter at night. */
    static float daylight(GlobeKind kind, int phase)
    {
        if (kind == GlobeKind.STAR_GLOBE)
            return 0.22f;
        if (kind == GlobeKind.NETHER_JAR || kind == GlobeKind.AQUARIUM || kind == GlobeKind.STORM_GLOBE)
            return 1;
        return switch (phase)
        {
            case GlobeSavedData.PHASE_SUNSET -> 0.68f;
            case GlobeSavedData.PHASE_NIGHT -> 0.22f;
            case GlobeSavedData.PHASE_SUNRISE -> 0.74f;
            default -> 1;
        };
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness)
    {
        return fogColor;
    }

    @Override
    public boolean isFoggyAt(int x, int y)
    {
        return false;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX, double camY, double camZ,
                                Matrix4f modelViewMatrix, Matrix4f projectionMatrix)
    {
        return true;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog)
    {
        if (camera.getFluidInCamera() == FogType.LAVA || camera.getFluidInCamera() == FogType.POWDER_SNOW)
            return true;
        Look look = look();
        float[][] c = colours(look);
        float flash = GlobeEffects.flash(partialTick);
        float seconds = (ticks + partialTick) / 20f;

        FogRenderer.setupNoFog();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = new Matrix4f(modelViewMatrix);

        // The dome: the top colour overhead, fading to the horizon colour (and that below, to meet the fog).
        BufferBuilder dome = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int lat = 0; lat < LAT; lat++)
            for (int lon = 0; lon < LON; lon++)
            {
                domeVertex(dome, m, lat, lon, c, flash);
                domeVertex(dome, m, lat + 1, lon, c, flash);
                domeVertex(dome, m, lat + 1, lon + 1, c, flash);
                domeVertex(dome, m, lat, lon + 1, c, flash);
            }
        draw(dome);

        // Stars, the sun or moon, the northern lights and shooting stars, added on top.
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        BufferBuilder glow = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float stars = starriness(look);
        if (stars > 0)
            for (int i = 0; i < (look.kind() == GlobeKind.STAR_GLOBE ? STARS.length : STARS.length * 2 / 3); i++)
            {
                float[] s = STARS[i];
                float twinkle = 0.6f + 0.4f * (float) Math.sin(seconds * 2.5f + s[4] * 7);
                billboard(glow, m, s[0], s[1], s[2], s[3], 1, 1, 0.97f, stars * twinkle);
            }
        sunOrMoon(glow, m, look);
        if (look.kind() == GlobeKind.STAR_GLOBE)
        {
            aurora(glow, m, seconds);
            shootingStars(glow, m, ticks + partialTick);
        }
        else if (look.phase() == GlobeSavedData.PHASE_NIGHT && look.kind() != GlobeKind.NETHER_JAR && look.kind() != GlobeKind.AQUARIUM)
            shootingStars(glow, m, ticks + partialTick);
        draw(glow);

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        return true;
    }

    private static void draw(BufferBuilder buffer)
    {
        MeshData mesh = buffer.build();
        if (mesh != null)
            BufferUploader.drawWithShader(mesh);
    }

    /** How bright the stars are: full at night (and always in the star globe), faint at dusk and dawn. */
    private static float starriness(Look look)
    {
        return switch (look.kind())
        {
            case STAR_GLOBE -> 1;
            case NETHER_JAR, AQUARIUM, STORM_GLOBE -> 0;
            default -> switch (look.phase())
            {
                case GlobeSavedData.PHASE_NIGHT -> 0.9f;
                case GlobeSavedData.PHASE_SUNSET, GlobeSavedData.PHASE_SUNRISE -> 0.2f;
                default -> 0;
            };
        };
    }

    private static void domeVertex(BufferBuilder out, Matrix4f m, int lat, int lon, float[][] c, float flash)
    {
        // Latitude runs from straight up (0) to a little below the horizon (LAT).
        double theta = Math.PI * 0.58 * lat / LAT, phi = Math.PI * 2 * lon / LON;
        float y = (float) Math.cos(theta), r = (float) Math.sin(theta);
        float t = (float) Math.pow(Math.max(0, Math.min(1, 1 - y)), 1.6);
        float red = c[0][0] + (c[1][0] - c[0][0]) * t, green = c[0][1] + (c[1][1] - c[0][1]) * t, blue = c[0][2] + (c[1][2] - c[0][2]) * t;
        red += (1 - red) * flash * 0.7f;
        green += (1 - green) * flash * 0.7f;
        blue += (1 - blue) * flash * 0.7f;
        out.addVertex(m, (float) Math.cos(phi) * r * RADIUS, y * RADIUS, (float) Math.sin(phi) * r * RADIUS).setColor(red, green, blue, 1);
    }

    /** A little square facing you at this point of the sky (a unit direction), {@code size} across at the dome. */
    private static void billboard(BufferBuilder out, Matrix4f m, float x, float y, float z, float size, float r, float g, float b, float a)
    {
        Vector3f d = new Vector3f(x, y, z).normalize();
        Vector3f side = new Vector3f(0, 1, 0).cross(d);
        if (side.lengthSquared() < 1e-6f)
            side.set(1, 0, 0);
        side.normalize(size / 2);
        Vector3f up = new Vector3f(d).cross(side).normalize(size / 2);
        Vector3f p = new Vector3f(d).mul(RADIUS * 0.98f);
        out.addVertex(m, p.x - side.x - up.x, p.y - side.y - up.y, p.z - side.z - up.z).setColor(r, g, b, a);
        out.addVertex(m, p.x + side.x - up.x, p.y + side.y - up.y, p.z + side.z - up.z).setColor(r, g, b, a);
        out.addVertex(m, p.x + side.x + up.x, p.y + side.y + up.y, p.z + side.z + up.z).setColor(r, g, b, a);
        out.addVertex(m, p.x - side.x + up.x, p.y - side.y + up.y, p.z - side.z + up.z).setColor(r, g, b, a);
    }

    /** The sun high at midday, low in the west at sunset and the east at sunrise; the moon at night. */
    private static void sunOrMoon(BufferBuilder out, Matrix4f m, Look look)
    {
        if (look.kind() == GlobeKind.NETHER_JAR || look.kind() == GlobeKind.AQUARIUM || look.kind() == GlobeKind.STORM_GLOBE)
            return;
        boolean night = look.kind() == GlobeKind.STAR_GLOBE || look.phase() == GlobeSavedData.PHASE_NIGHT;
        double elevation, azimuth;
        switch (night ? GlobeSavedData.PHASE_NIGHT : look.phase())
        {
            case GlobeSavedData.PHASE_SUNSET -> { elevation = 6; azimuth = 180; }
            case GlobeSavedData.PHASE_SUNRISE -> { elevation = 6; azimuth = 0; }
            case GlobeSavedData.PHASE_NIGHT -> { elevation = 55; azimuth = 210; }
            default -> { elevation = 68; azimuth = 100; }
        }
        double e = Math.toRadians(elevation), a = Math.toRadians(azimuth);
        float x = (float) (Math.cos(e) * Math.cos(a)), y = (float) Math.sin(e), z = (float) (Math.cos(e) * Math.sin(a));
        if (night)
        {
            billboard(out, m, x, y, z, 16, 0.6f, 0.65f, 0.8f, 0.12f);
            billboard(out, m, x, y, z, 7, 0.86f, 0.88f, 0.95f, 0.95f);
        }
        else
        {
            boolean low = look.phase() != GlobeSavedData.PHASE_DAY;
            billboard(out, m, x, y, z, 30, 1, low ? 0.55f : 0.9f, low ? 0.3f : 0.6f, 0.18f);
            billboard(out, m, x, y, z, 11, 1, low ? 0.78f : 0.97f, low ? 0.5f : 0.8f, 1);
        }
    }

    /** The northern lights: a curtain of green and teal, rippling slowly across the north. */
    private static void aurora(BufferBuilder out, Matrix4f m, float seconds)
    {
        int bands = 48;
        for (int i = 0; i < bands; i++)
        {
            float t0 = (float) i / bands, t1 = (float) (i + 1) / bands;
            float[] a = auroraPoint(t0, seconds), b = auroraPoint(t1, seconds);
            float alpha0 = 0.18f + 0.14f * (float) Math.sin(t0 * 17 + seconds * 1.1f);
            float alpha1 = 0.18f + 0.14f * (float) Math.sin(t1 * 17 + seconds * 1.1f);
            // Bright at the bottom edge, fading upwards.
            out.addVertex(m, a[0], a[1], a[2]).setColor(0.3f, 1f, 0.6f, alpha0);
            out.addVertex(m, b[0], b[1], b[2]).setColor(0.3f, 1f, 0.6f, alpha1);
            out.addVertex(m, b[0], b[1] + 26, b[2]).setColor(0.2f, 0.6f, 0.9f, 0);
            out.addVertex(m, a[0], a[1] + 26, a[2]).setColor(0.2f, 0.6f, 0.9f, 0);
        }
    }

    private static float[] auroraPoint(float t, float seconds)
    {
        double angle = Math.toRadians(200 + t * 140);
        float wave = (float) Math.sin(t * 9 + seconds * 0.35f) * 8;
        float dist = RADIUS * 0.9f;
        return new float[] {(float) Math.cos(angle) * dist, 24 + wave + (float) Math.sin(t * 23 + seconds) * 2, (float) Math.sin(angle) * dist - 20};
    }

    /** Now and then a shooting star streaks across the sky. */
    private static void shootingStars(BufferBuilder out, Matrix4f m, float ticks)
    {
        long slot = (long) Math.floor(ticks / 70);
        Random random = new Random(slot * 6151L + 17);
        if (random.nextInt(2) != 0)
            return;
        float age = (ticks - slot * 70) / 14f;
        if (age > 1)
            return;
        double a = random.nextDouble() * Math.PI * 2, e = Math.toRadians(35 + random.nextDouble() * 35);
        for (int k = 0; k < 8; k++)
        {
            float p = age - k * 0.035f;
            if (p < 0)
                continue;
            double aa = a + p * 0.5, ee = e - p * 0.25;
            billboard(out, m, (float) (Math.cos(ee) * Math.cos(aa)), (float) Math.sin(ee), (float) (Math.cos(ee) * Math.sin(aa)),
                    0.9f - k * 0.08f, 1, 0.97f, 0.85f, (1 - age) * (1 - k / 8f));
        }
    }

    /** The light inside follows the sky: dimmer at dusk, dark (and blue) at night, warm in the nether jar. */
    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker, float skyLight,
                                     int pixelX, int pixelY, Vector3f colors)
    {
        Look look = look();
        if (look.kind() == GlobeKind.NETHER_JAR)
        {
            colors.mul(1.0f, 0.82f, 0.7f).add(0.06f, 0.015f, 0);
            return;
        }
        float day = daylight(look.kind(), look.phase());
        if (day >= 1)
            return;
        float block = LightTexture.getBrightness(level.dimensionType(), pixelX) * 1.5f;
        float sky = LightTexture.getBrightness(level.dimensionType(), pixelY) * day;
        boolean warm = look.phase() == GlobeSavedData.PHASE_SUNSET || look.phase() == GlobeSavedData.PHASE_SUNRISE;
        float sr = warm ? 1.0f : 0.62f, sg = warm ? 0.8f : 0.68f, sb = warm ? 0.68f : 1.0f;
        float r = block + sky * sr, g = block * ((block * 0.6f + 0.4f) * 0.6f + 0.4f) + sky * sg, b = block * (block * block * 0.6f + 0.4f) + sky * sb;
        colors.set(Math.min(1, r * 0.96f + 0.03f), Math.min(1, g * 0.96f + 0.03f), Math.min(1, b * 0.96f + 0.04f));
    }

    @EventBusSubscriber(modid = WorldsInGlassMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration
    {
        private Registration() {}

        @SubscribeEvent
        public static void register(RegisterDimensionSpecialEffectsEvent event)
        {
            event.register(ResourceLocation.fromNamespaceAndPath(WorldsInGlassMod.MODID, "globe"), new GlobeSky());
        }
    }

    /** The haze inside a globe takes the sky's horizon colour, so the giant world fades into the sky. */
    @EventBusSubscriber(modid = WorldsInGlassMod.MODID, value = Dist.CLIENT)
    public static final class Fog
    {
        private Fog() {}

        @SubscribeEvent
        public static void onFogColour(ViewportEvent.ComputeFogColor event)
        {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || level.dimension() != GlobeSpace.LEVEL || event.getCamera().getFluidInCamera() != FogType.NONE)
                return;
            float[] horizon = colours(look())[1];
            float flash = GlobeEffects.flash((float) event.getPartialTick());
            event.setRed(horizon[0] + (1 - horizon[0]) * flash * 0.7f);
            event.setGreen(horizon[1] + (1 - horizon[1]) * flash * 0.7f);
            event.setBlue(horizon[2] + (1 - horizon[2]) * flash * 0.7f);
        }
    }
}
