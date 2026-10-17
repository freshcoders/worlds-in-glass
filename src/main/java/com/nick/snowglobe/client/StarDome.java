package com.nick.snowglobe.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Inside the star globe, the night sky is painted on the inside of the glass itself: stars twinkling over the dome,
 * the northern lights rippling round its upper half, and now and then a shooting star. (The real sky is often hidden
 * behind the giant room outside, so the globe brings its own.)
 */
@EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT)
public final class StarDome
{
    private static final float[][] STARS = new float[420][];

    static
    {
        Random random = new Random(4021);
        for (int i = 0; i < STARS.length; i++)
        {
            // Over the upper part of the dome and a little below the middle: a direction, a size and a twinkle phase.
            double y = -0.15 + random.nextDouble() * 1.15, angle = random.nextDouble() * Math.PI * 2, r = Math.sqrt(Math.max(0, 1 - y * y));
            STARS[i] = new float[] {(float) (Math.cos(angle) * r), (float) Math.min(y, 1), (float) (Math.sin(angle) * r),
                    0.5f + random.nextFloat() * 0.7f, random.nextFloat() * 6.28f};
        }
    }

    private StarDome() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || OutsideView.insideKind() != GlobeKind.STAR_GLOBE)
            return;
        Vec3 center = OutsideView.insideCenter();
        double radius = OutsideView.insideRadius();
        if (center == null || radius <= 0)
            return;
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float ticks = mc.level == null ? 0 : mc.level.getGameTime() % 240000 + partial;
        float seconds = ticks / 20f;
        Vec3 camera = event.getCamera().getPosition();

        // The camera's turn: in the render system's model-view already at some stages, only in the event at others.
        Matrix4f m = RenderSystem.getModelViewMatrix().equals(new Matrix4f(), 1e-5f) ? new Matrix4f(event.getModelViewMatrix()) : new Matrix4f();
        m.translate((float) (center.x - camera.x), (float) (center.y - camera.y), (float) (center.z - camera.z));
        // Just inside the glass, so nothing outside can hide it; stars a little further in than the lights.
        float r = (float) radius - 0.08f;
        // Star size grows with the dome, so they read as pinpricks whatever the globe's size.
        float starSize = (float) Math.max(0.08, radius * 0.012);

        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder out = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (float[] s : STARS)
        {
            float twinkle = 0.55f + 0.45f * (float) Math.sin(seconds * 2.3f + s[4] * 7);
            square(out, m, s[0], s[1], s[2], r, starSize * s[3], 1, 0.97f, 0.9f, twinkle);
        }
        aurora(out, m, r * 0.98f, seconds);
        shootingStars(out, m, r, starSize, ticks);

        MeshData mesh = out.build();
        if (mesh != null)
            BufferUploader.drawWithShader(mesh);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }

    /** A little square at this direction from the dome's centre, at distance {@code r}, facing the centre. */
    private static void square(BufferBuilder out, Matrix4f m, float x, float y, float z, float r, float size, float red, float green,
                               float blue, float alpha)
    {
        Vector3f d = new Vector3f(x, y, z).normalize();
        Vector3f side = new Vector3f(0, 1, 0).cross(d);
        if (side.lengthSquared() < 1e-6f)
            side.set(1, 0, 0);
        side.normalize(size / 2);
        Vector3f up = new Vector3f(d).cross(side).normalize(size / 2);
        Vector3f p = new Vector3f(d).mul(r);
        out.addVertex(m, p.x - side.x - up.x, p.y - side.y - up.y, p.z - side.z - up.z).setColor(red, green, blue, alpha);
        out.addVertex(m, p.x + side.x - up.x, p.y + side.y - up.y, p.z + side.z - up.z).setColor(red, green, blue, alpha);
        out.addVertex(m, p.x + side.x + up.x, p.y + side.y + up.y, p.z + side.z + up.z).setColor(red, green, blue, alpha);
        out.addVertex(m, p.x - side.x + up.x, p.y - side.y + up.y, p.z - side.z + up.z).setColor(red, green, blue, alpha);
    }

    /** The northern lights: a green curtain round the dome, bright along its lower edge and fading upwards. */
    private static void aurora(BufferBuilder out, Matrix4f m, float r, float seconds)
    {
        int bands = 72;
        for (int i = 0; i < bands; i++)
        {
            float t0 = (float) i / bands, t1 = (float) (i + 1) / bands;
            float[] a = curtain(t0, seconds, r), b = curtain(t1, seconds, r);
            float alpha0 = 0.16f + 0.12f * (float) Math.sin(t0 * 23 + seconds * 1.1f);
            float alpha1 = 0.16f + 0.12f * (float) Math.sin(t1 * 23 + seconds * 1.1f);
            float height = r * 0.28f;
            out.addVertex(m, a[0], a[1], a[2]).setColor(0.3f, 1f, 0.6f, alpha0);
            out.addVertex(m, b[0], b[1], b[2]).setColor(0.3f, 1f, 0.6f, alpha1);
            out.addVertex(m, b[0] * 0.92f, b[1] + height, b[2] * 0.92f).setColor(0.25f, 0.55f, 1f, 0);
            out.addVertex(m, a[0] * 0.92f, a[1] + height, a[2] * 0.92f).setColor(0.25f, 0.55f, 1f, 0);
        }
    }

    /** A point on the curtain's lower edge, wavering up and down as it goes round. */
    private static float[] curtain(float t, float seconds, float r)
    {
        double angle = Math.PI * 2 * t;
        float elevation = 0.38f + 0.07f * (float) Math.sin(t * 13 + seconds * 0.35f);
        float flat = (float) Math.sqrt(1 - elevation * elevation) * r;
        return new float[] {(float) Math.cos(angle) * flat, elevation * r, (float) Math.sin(angle) * flat};
    }

    /** Every few seconds, maybe a shooting star across the dome. */
    private static void shootingStars(BufferBuilder out, Matrix4f m, float r, float size, float ticks)
    {
        long slot = (long) Math.floor(ticks / 60);
        Random random = new Random(slot * 7919L + 3);
        if (random.nextInt(2) != 0)
            return;
        float age = (ticks - slot * 60) / 14f;
        if (age > 1)
            return;
        double a = random.nextDouble() * Math.PI * 2, e = Math.toRadians(30 + random.nextDouble() * 40);
        for (int k = 0; k < 10; k++)
        {
            float p = age - k * 0.03f;
            if (p < 0)
                continue;
            double aa = a + p * 0.7, ee = e - p * 0.3;
            square(out, m, (float) (Math.cos(ee) * Math.cos(aa)), (float) Math.sin(ee), (float) (Math.cos(ee) * Math.sin(aa)), r,
                    size * (1.6f - k * 0.12f), 1, 0.97f, 0.85f, (1 - age) * (1 - k / 10f));
        }
    }
}
