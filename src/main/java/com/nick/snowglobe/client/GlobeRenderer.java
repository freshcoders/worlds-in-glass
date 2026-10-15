package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.snowglobe.GlobeBlockEntity;
import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.GlobeSpace;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Random;

/** Draws a globe's contents as a miniature, plus (for snow globes) the glass ball and falling snow. */
public class GlobeRenderer implements BlockEntityRenderer<GlobeBlockEntity>
{
    private static final ResourceLocation GLASS = new ResourceLocation(SnowGlobeMod.MODID, "textures/entity/globe_glass.png");
    private static final ResourceLocation SNOW = new ResourceLocation(SnowGlobeMod.MODID, "textures/entity/snowflake.png");

    // Snow globe ball, in block units: sits on the pedestal's gold rim.
    private static final float BALL_RADIUS = 4.5f / 16f;
    private static final float BALL_CENTER_Y = 6.5f / 16f;
    private static final int BALL_LATITUDES = 14;
    private static final int BALL_LONGITUDES = 28;

    // Terrarium: the miniature fills the glass case above the wooden base.
    private static final float CASE_WIDTH = 10f / 16f;
    private static final float CASE_FLOOR = 1f / 16f;

    private static final int FLAKES = 36;
    private static final float[][] FLAKE_SEEDS = new float[FLAKES][];

    static
    {
        Random random = new Random(1225);
        for (int i = 0; i < FLAKES; i++)
        {
            double angle = random.nextDouble() * Math.PI * 2, dist = Math.sqrt(random.nextDouble());
            FLAKE_SEEDS[i] = new float[] {(float) (Math.cos(angle) * dist), (float) (Math.sin(angle) * dist),
                    random.nextFloat(), 0.6f + random.nextFloat() * 0.5f, random.nextFloat() * 6.28f};
        }
    }

    private final BlockRenderDispatcher blocks;

    public GlobeRenderer(BlockEntityRendererProvider.Context context)
    {
        this.blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(GlobeBlockEntity globe, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay)
    {
        float seconds = globe.getLevel() == null ? 0 : (globe.getLevel().getGameTime() % 24000 + partialTick) / 20f;
        renderGlobe(globe.kind(), globe.getSnapshot(), poseStack, buffers, light, seconds, blocks);
    }

    /** Shared by the placed block and the item, so a globe looks the same in your hand as on a shelf. */
    public static void renderGlobe(GlobeKind kind, @Nullable GlobeSnapshot snapshot, PoseStack poseStack, MultiBufferSource buffers,
                                   int light, float seconds, BlockRenderDispatcher blocks)
    {
        if (kind == GlobeKind.SNOW_GLOBE)
        {
            // The dome's cells reach about 8.9 cells from the centre; fit that just inside the glass.
            float cell = BALL_RADIUS * 0.94f / 8.9f;
            if (snapshot != null)
                renderMiniature(snapshot, poseStack, buffers, light, blocks, 0.5f, BALL_CENTER_Y, 0.5f, cell, GlobeSpace.SIZE / 2f);
            renderSnow(poseStack, buffers, light, seconds);
            renderBall(poseStack, buffers, light);
        }
        else if (snapshot != null)
        {
            float cell = CASE_WIDTH / GlobeSpace.SIZE;
            renderMiniature(snapshot, poseStack, buffers, light, blocks, 0.5f, CASE_FLOOR + 0.002f, 0.5f, cell, 0);
        }
    }

    /** Draws the snapshot's blocks scaled by {@code cell}, with grid point (8, pivotY, 8) placed at (x, y, z). */
    private static void renderMiniature(GlobeSnapshot snapshot, PoseStack poseStack, MultiBufferSource buffers, int light,
                                        BlockRenderDispatcher blocks, float x, float y, float z, float cell, float pivotY)
    {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.scale(cell, cell, cell);
        poseStack.translate(-GlobeSpace.SIZE / 2f, -pivotY, -GlobeSpace.SIZE / 2f);
        for (BlockPos pos : snapshot.visibleBlocks())
        {
            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            blocks.renderSingleBlock(snapshot.get(pos.getX(), pos.getY(), pos.getZ()), poseStack, buffers, light,
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** Little flakes drifting down through the ball, swaying as they fall, and starting over at the top. */
    private static void renderSnow(PoseStack poseStack, MultiBufferSource buffers, int light, float seconds)
    {
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(SNOW));
        PoseStack.Pose pose = poseStack.last();
        float half = 0.22f / 16f;
        for (float[] seed : FLAKE_SEEDS)
        {
            // Height runs from near the top of the ball down to the ground (about half-way down).
            float fall = (seconds * seed[3] * 0.18f + seed[2]) % 1f;
            float h = 0.85f - fall * 1.3f;
            float spread = (float) Math.sqrt(Math.max(0, 1 - h * h)) * 0.8f;
            float sway = (float) Math.sin(seconds * 1.3f + seed[4]) * 0.06f;
            float fx = 0.5f + (seed[0] * spread + sway) * BALL_RADIUS;
            float fy = BALL_CENTER_Y + h * BALL_RADIUS;
            float fz = 0.5f + (seed[1] * spread) * BALL_RADIUS;
            cube(out, pose, fx, fy, fz, half, light);
        }
    }

    /** The glass ball: a sphere with a faint tint and a highlight, seen from both inside and out. */
    private static void renderBall(PoseStack poseStack, MultiBufferSource buffers, int light)
    {
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(GLASS));
        PoseStack.Pose pose = poseStack.last();
        for (int lat = 0; lat < BALL_LATITUDES; lat++)
            for (int lon = 0; lon < BALL_LONGITUDES; lon++)
            {
                ballVertex(out, pose, lat, lon, light);
                ballVertex(out, pose, lat + 1, lon, light);
                ballVertex(out, pose, lat + 1, lon + 1, light);
                ballVertex(out, pose, lat, lon + 1, light);
            }
    }

    private static void ballVertex(VertexConsumer out, PoseStack.Pose pose, int lat, int lon, int light)
    {
        double theta = Math.PI * lat / BALL_LATITUDES;
        double phi = Math.PI * 2 * lon / BALL_LONGITUDES;
        float nx = (float) (Math.sin(theta) * Math.cos(phi));
        float ny = (float) Math.cos(theta);
        float nz = (float) (Math.sin(theta) * Math.sin(phi));
        vertex(out, pose.pose(), pose.normal(), 0.5f + nx * BALL_RADIUS, BALL_CENTER_Y + ny * BALL_RADIUS, 0.5f + nz * BALL_RADIUS,
                (float) lon / BALL_LONGITUDES, (float) lat / BALL_LATITUDES, nx, ny, nz, light);
    }

    private static void cube(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float h, int light)
    {
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        float x0 = x - h, x1 = x + h, y0 = y - h, y1 = y + h, z0 = z - h, z1 = z + h;
        quad(out, m, n, light, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(out, m, n, light, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, m, n, light, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(out, m, n, light, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, m, n, light, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, m, n, light, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(VertexConsumer out, Matrix4f m, Matrix3f n, int light, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz)
    {
        vertex(out, m, n, ax, ay, az, 0, 0, nx, ny, nz, light);
        vertex(out, m, n, bx, by, bz, 0, 1, nx, ny, nz, light);
        vertex(out, m, n, cx, cy, cz, 1, 1, nx, ny, nz, light);
        vertex(out, m, n, dx, dy, dz, 1, 0, nx, ny, nz, light);
    }

    private static void vertex(VertexConsumer out, Matrix4f m, Matrix3f n, float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light)
    {
        out.vertex(m, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light).normal(n, nx, ny, nz).endVertex();
    }
}
