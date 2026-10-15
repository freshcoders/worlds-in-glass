package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.snowglobe.GlobeBlockEntity;
import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.GlobeSpace;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Random;

/** Draws a globe's contents as a miniature, plus (for snow globes) the glass ball and falling snow. */
public class GlobeRenderer implements BlockEntityRenderer<GlobeBlockEntity>
{
    /** Plain white glass in the block atlas; the renderer sets how see-through each part of the ball is. */
    private static final ResourceLocation GLASS = new ResourceLocation(SnowGlobeMod.MODID, "block/globe_ball");
    private static final ResourceLocation SNOW = new ResourceLocation(SnowGlobeMod.MODID, "textures/entity/snowflake.png");

    // Snow globe ball, in block units: sits on the pedestal's gold rim.
    private static final float BALL_RADIUS = 4.5f / 16f;
    private static final float BALL_CENTER_Y = 6.5f / 16f;
    private static final int BALL_LATITUDES = 14;
    private static final int BALL_LONGITUDES = 28;
    /** Direction the light comes from, for the glint on the glass. */
    private static final Vec3 LIGHT = new Vec3(0.35, 1, 0.25).normalize();
    /** Items don't know where the viewer is; this matches how they're usually seen (front, slightly above). */
    private static final Vec3 ITEM_EYE = new Vec3(0.6, 0.45, 0.65).normalize();

    // Terrarium: the miniature fills the glass case above the wooden base.
    private static final float CASE_WIDTH = 10f / 16f;
    private static final float CASE_FLOOR = 1f / 16f;

    private static final int FLAKES = 70;
    /** Half the width of a snowflake: about a quarter of a miniature block. */
    private static final float FLAKE_HALF = 0.06f / 16f;
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
        // Where the camera is, relative to the ball's centre, so the glass can catch the light at its edges.
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        BlockPos pos = globe.getBlockPos();
        Vec3 eye = camera.subtract(pos.getX() + 0.5, pos.getY() + BALL_CENTER_Y, pos.getZ() + 0.5);

        // The mobs living in this globe, brought up to date with the latest positions from the server.
        MiniEntities mobs;
        if (globe.renderState instanceof MiniEntities existing)
            mobs = existing;
        else
            globe.renderState = mobs = new MiniEntities();
        ListTag update = globe.takeEntityUpdate();
        if (update != null && globe.getLevel() != null)
            mobs.update(update, globe.getLevel());

        renderGlobe(globe.kind(), globe.getSnapshot(), poseStack, buffers, light, seconds, blocks, eye,
                globe.getLevel() == null ? null : mobs, globe.getLevel(), partialTick);
    }

    /**
     * Shared by the placed block and the item, so a globe looks the same in your hand as on a shelf.
     * {@code eye} is the viewer's position relative to the ball's centre, or null when unknown (items).
     * {@code mobs} are the globe's living residents, or null when not shown (items).
     */
    public static void renderGlobe(GlobeKind kind, @Nullable GlobeSnapshot snapshot, PoseStack poseStack, MultiBufferSource buffers,
                                   int light, float seconds, BlockRenderDispatcher blocks, @Nullable Vec3 eye,
                                   @Nullable MiniEntities mobs, @Nullable Level level, float partialTick)
    {
        if (kind == GlobeKind.SNOW_GLOBE)
        {
            // The dome's cells reach about 8.9 cells from the centre; fit that just inside the glass.
            float cell = BALL_RADIUS * 0.94f / 8.9f;
            pushMiniature(poseStack, BALL_CENTER_Y, cell, GlobeSpace.SIZE / 2f);
            renderContents(snapshot, mobs, level, partialTick, poseStack, buffers, light, blocks);
            poseStack.popPose();
            renderSnow(poseStack, buffers, light, seconds);
            renderBall(poseStack, buffers, light, eye == null ? null : eye.normalize());
        }
        else
        {
            pushMiniature(poseStack, CASE_FLOOR + 0.002f, CASE_WIDTH / GlobeSpace.SIZE, 0);
            renderContents(snapshot, mobs, level, partialTick, poseStack, buffers, light, blocks);
            poseStack.popPose();
        }
    }

    /** Switches to globe-space units scaled by {@code cell}, with grid point (8, pivotY, 8) placed at (0.5, y, 0.5). */
    private static void pushMiniature(PoseStack poseStack, float y, float cell, float pivotY)
    {
        poseStack.pushPose();
        poseStack.translate(0.5f, y, 0.5f);
        poseStack.scale(cell, cell, cell);
        poseStack.translate(-GlobeSpace.SIZE / 2f, -pivotY, -GlobeSpace.SIZE / 2f);
    }

    private static void renderContents(@Nullable GlobeSnapshot snapshot, @Nullable MiniEntities mobs, @Nullable Level level,
                                       float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light,
                                       BlockRenderDispatcher blocks)
    {
        if (snapshot != null)
            for (BlockPos pos : snapshot.visibleBlocks())
            {
                poseStack.pushPose();
                poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                blocks.renderSingleBlock(snapshot.get(pos.getX(), pos.getY(), pos.getZ()), poseStack, buffers, light,
                        OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
                poseStack.popPose();
            }
        if (mobs != null && level != null)
            mobs.render(poseStack, buffers, light, partialTick, level);
    }

    /** Little flakes drifting down through the ball, swaying as they fall, and starting over at the top. */
    private static void renderSnow(PoseStack poseStack, MultiBufferSource buffers, int light, float seconds)
    {
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(SNOW));
        PoseStack.Pose pose = poseStack.last();
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
            cube(out, pose, fx, fy, fz, FLAKE_HALF, light);
        }
    }

    /**
     * The glass ball: nearly clear where you look straight through it, brighter towards its outline
     * (like real glass), so its round shape reads from any angle. Seen from both inside and out.
     */
    private static void renderBall(PoseStack poseStack, MultiBufferSource buffers, int light, @Nullable Vec3 eye)
    {
        // The translucent block sheet is drawn after the solid and cutout ones the miniature uses,
        // so the glass goes on top of the scene instead of hiding it.
        VertexConsumer out = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        TextureAtlasSprite glass = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(GLASS);
        float u = glass.getU(8), v = glass.getV(8);
        PoseStack.Pose pose = poseStack.last();
        for (int lat = 0; lat < BALL_LATITUDES; lat++)
            for (int lon = 0; lon < BALL_LONGITUDES; lon++)
            {
                // Counter-clockwise seen from outside, so the faces aren't culled.
                ballVertex(out, pose, lat, lon, light, eye, u, v);
                ballVertex(out, pose, lat, lon + 1, light, eye, u, v);
                ballVertex(out, pose, lat + 1, lon + 1, light, eye, u, v);
                ballVertex(out, pose, lat + 1, lon, light, eye, u, v);
            }
    }

    private static void ballVertex(VertexConsumer out, PoseStack.Pose pose, int lat, int lon, int light, @Nullable Vec3 eye, float u, float v)
    {
        double theta = Math.PI * lat / BALL_LATITUDES;
        double phi = Math.PI * 2 * lon / BALL_LONGITUDES;
        float nx = (float) (Math.sin(theta) * Math.cos(phi));
        float ny = (float) Math.cos(theta);
        float nz = (float) (Math.sin(theta) * Math.sin(phi));
        Vec3 e = eye != null ? eye : ITEM_EYE;
        // Edge glow: 0 where you look straight through the glass, 1 at its outline.
        double facing = nx * e.x + ny * e.y + nz * e.z;
        double edge = 1 - Math.abs(facing);
        double opacity = 0.12 + 0.8 * edge * edge * edge;
        // One small glint, on the side facing you, where the light from above would reflect towards you.
        if (facing > 0)
        {
            Vec3 half = e.add(LIGHT).normalize();
            double shine = Math.max(0, nx * half.x + ny * half.y + nz * half.z);
            opacity += Math.pow(shine, 60) * 1.5;
        }
        int alpha = (int) (255 * Math.min(1, opacity));
        vertex(out, pose.pose(), pose.normal(), 0.5f + nx * BALL_RADIUS, BALL_CENTER_Y + ny * BALL_RADIUS, 0.5f + nz * BALL_RADIUS,
                u, v, nx, ny, nz, light, alpha);
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
        vertex(out, m, n, ax, ay, az, 0, 0, nx, ny, nz, light, 255);
        vertex(out, m, n, bx, by, bz, 0, 1, nx, ny, nz, light, 255);
        vertex(out, m, n, cx, cy, cz, 1, 1, nx, ny, nz, light, 255);
        vertex(out, m, n, dx, dy, dz, 1, 0, nx, ny, nz, light, 255);
    }

    private static void vertex(VertexConsumer out, Matrix4f m, Matrix3f n, float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light, int alpha)
    {
        out.vertex(m, x, y, z).color(255, 255, 255, alpha).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light).normal(n, nx, ny, nz).endVertex();
    }
}
