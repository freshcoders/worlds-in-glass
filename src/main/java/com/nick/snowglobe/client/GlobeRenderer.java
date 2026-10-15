package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.snowglobe.GlobeBlockEntity;
import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.GlobeSpace;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Draws a globe's contents as a miniature (blocks, water and living mobs), plus each kind's glass and
 * weather: snow in the snow globe; clouds, rain and lightning in the storm globe; a bottle round the ship.
 */
public class GlobeRenderer implements BlockEntityRenderer<GlobeBlockEntity>
{
    /** Plain white glass in the block atlas; the renderer sets its tint and how see-through each part is. */
    private static final ResourceLocation GLASS = ResourceLocation.fromNamespaceAndPath(SnowGlobeMod.MODID, "block/globe_ball");
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(SnowGlobeMod.MODID, "textures/entity/snowflake.png");

    // Ball (snow and storm globes), in block units: sits on the pedestal's rim.
    private static final float BALL_RADIUS = 4.5f / 16f;
    private static final float BALL_CENTER_Y = 6.5f / 16f;
    private static final int BALL_LATITUDES = 14;
    private static final int BALL_LONGITUDES = 28;

    // Terrarium: the miniature fills the glass case above the wooden base.
    private static final float CASE_WIDTH = 10f / 16f;
    private static final float CASE_FLOOR = 1f / 16f;

    // Bottle, lying along x on its stand: a profile (x, radius) in pixels, turned round the bottle's axis.
    private static final float BOTTLE_Y = 5.25f / 16f;
    private static final float BOTTLE_MID_X = 6.75f / 16f;
    private static final float BOTTLE_CELL = 0.6f / 16f;
    private static final float[][] BOTTLE_PROFILE = {{1.5f, 0}, {1.5f, 3.85f}, {12f, 3.85f}, {13.2f, 1.5f}, {15f, 1.5f}};
    private static final int BOTTLE_SEGMENTS = 24;

    /** Direction the light comes from, for the glint on the glass. */
    private static final Vec3 LIGHT = new Vec3(0.35, 1, 0.25).normalize();
    /** Items don't know where the viewer is; this matches how they're usually seen (front, slightly above). */
    private static final Vec3 ITEM_EYE = new Vec3(0.6, 0.45, 0.65).normalize();

    private static final float[][] FLAKES = seeds(70, 1225);
    private static final float[][] DROPS = seeds(90, 77);
    private static final float[][] CLOUDS = seeds(10, 4242);
    /** Ticks between possible lightning strikes; about one in three slots actually strikes. */
    private static final int STRIKE_SLOT = 50;

    private final BlockRenderDispatcher blocks;

    public GlobeRenderer(BlockEntityRendererProvider.Context context)
    {
        this.blocks = context.getBlockRenderDispatcher();
    }

    /** Per-particle random numbers: position in a unit disc, a phase, a speed and a sway phase. */
    private static float[][] seeds(int count, long seed)
    {
        Random random = new Random(seed);
        float[][] out = new float[count][];
        for (int i = 0; i < count; i++)
        {
            double angle = random.nextDouble() * Math.PI * 2, dist = Math.sqrt(random.nextDouble());
            out[i] = new float[] {(float) (Math.cos(angle) * dist), (float) (Math.sin(angle) * dist),
                    random.nextFloat(), 0.6f + random.nextFloat() * 0.5f, random.nextFloat() * 6.28f};
        }
        return out;
    }

    /** Where a kind's glass is centred inside its block, for the edge glow. */
    private static Vec3 glassCenter(GlobeKind kind)
    {
        return kind == GlobeKind.SHIP_BOTTLE ? new Vec3(BOTTLE_MID_X, BOTTLE_Y, 0.5) : new Vec3(0.5, BALL_CENTER_Y, 0.5);
    }

    @Override
    public void render(GlobeBlockEntity globe, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay)
    {
        if (!(globe.getLevel() instanceof ClientLevel level))
            return;
        ClientGlobeState state;
        if (globe.renderState instanceof ClientGlobeState existing)
            state = existing;
        else
            globe.renderState = state = new ClientGlobeState();
        ListTag update = globe.takeEntityUpdate();
        if (update != null)
            state.mobs.update(update, level);

        // Where the camera is, relative to the glass's centre, so the glass can catch the light at its edges.
        BlockPos pos = globe.getBlockPos();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 eye = camera.subtract(Vec3.atLowerCornerOf(pos).add(glassCenter(globe.kind()))).normalize();

        float ticks = level.getGameTime() % 240000 + partialTick;
        renderGlobe(globe.kind(), globe.getSnapshot(), poseStack, buffers, light, ticks, blocks, eye, state, pos.asLong(), level, pos);
    }

    /**
     * Shared by the placed block and the item, so a globe looks the same in your hand as on a shelf.
     * {@code eye} is the direction to the viewer from the glass's centre, or null when unknown (items);
     * {@code state} holds the globe's mobs, or is null when there are none to show (items).
     */
    public static void renderGlobe(GlobeKind kind, @Nullable GlobeSnapshot snapshot, PoseStack poseStack, MultiBufferSource buffers,
                                   int light, float ticks, BlockRenderDispatcher blocks, @Nullable Vec3 eye,
                                   @Nullable ClientGlobeState state, long seed, @Nullable ClientLevel level, BlockPos where)
    {
        Vec3 view = eye != null ? eye : ITEM_EYE;
        float partialTick = ticks - (float) Math.floor(ticks);
        // Bigger globes hold more blocks in the same glass, so each block is drawn smaller.
        int size = snapshot != null ? snapshot.size() : GlobeSpace.DEFAULT_SIZE;
        float half = size / 2f;
        // Fit what's inside just within the ball: a full dome reaches about 1.11 half-sizes from its centre,
        // a smaller build less, so it's shown bigger. (Never zoom in further than an 8-block reach.)
        double reach = snapshot != null ? Math.min(snapshot.contentRadius(), half * 1.1125) : half * 1.1125;
        float ballCell = BALL_RADIUS * 0.94f / (float) Math.max(reach, 8);
        switch (kind)
        {
            case SNOW_GLOBE ->
            {
                pushMiniature(poseStack, 0.5f, BALL_CENTER_Y, ballCell, size, half);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks);
                poseStack.popPose();
                renderSnow(poseStack, buffers, light, ticks / 20f);
                renderBall(poseStack, buffers, light, view, 255, 255, 255, 0);
            }
            case STORM_GLOBE ->
            {
                pushMiniature(poseStack, 0.5f, BALL_CENTER_Y, ballCell, size, half);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks);
                poseStack.popPose();
                float flash = renderStorm(poseStack, buffers, light, ticks, seed, state, level, where);
                int grey = (int) (150 + 105 * flash);
                renderBall(poseStack, buffers, light, view, grey, grey + (int) (10 * (1 - flash)), grey + (int) (30 * (1 - flash)), flash * 0.35f);
            }
            case SHIP_BOTTLE ->
            {
                pushMiniature(poseStack, BOTTLE_MID_X, BOTTLE_Y, BOTTLE_CELL * GlobeSpace.DEFAULT_SIZE / size, size, half);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks);
                poseStack.popPose();
                renderBottle(poseStack, buffers, light, view);
            }
            default ->
            {
                pushMiniature(poseStack, 0.5f, CASE_FLOOR + 0.002f, CASE_WIDTH / size, size, 0);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks);
                poseStack.popPose();
            }
        }
    }

    /** Switches to globe-space units scaled by {@code cell}, with the grid's middle column at (x, y, 0.5), raised by pivotY. */
    private static void pushMiniature(PoseStack poseStack, float x, float y, float cell, int size, float pivotY)
    {
        poseStack.pushPose();
        poseStack.translate(x, y, 0.5f);
        poseStack.scale(cell, cell, cell);
        poseStack.translate(-size / 2f, -pivotY, -size / 2f);
    }

    private static void renderContents(@Nullable GlobeSnapshot snapshot, @Nullable ClientGlobeState state, @Nullable ClientLevel level,
                                       BlockPos where, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light,
                                       BlockRenderDispatcher blocks)
    {
        if (snapshot != null)
        {
            MiniBlockEntities blockEntities = MiniBlockEntities.of(snapshot, level);
            for (BlockPos pos : snapshot.visibleBlocks())
            {
                poseStack.pushPose();
                poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                blockEntities.render(pos, snapshot.get(pos.getX(), pos.getY(), pos.getZ()), poseStack, buffers, light, partialTick, blocks);
                poseStack.popPose();
            }
            if (level != null && !snapshot.fluidCells().isEmpty())
            {
                // Minecraft's water renderer, fed with the snapshot as its world; it writes in cell units.
                SnapshotView view = new SnapshotView(snapshot, level, where, light);
                VertexConsumer out = new SnapshotView.PosedConsumer(buffers.getBuffer(Sheets.translucentCullBlockSheet()), poseStack.last());
                for (BlockPos pos : snapshot.fluidCells())
                {
                    BlockState block = snapshot.get(pos.getX(), pos.getY(), pos.getZ());
                    blocks.renderLiquid(pos, view, out, block, block.getFluidState());
                }
            }
        }
        if (state != null && level != null)
            state.mobs.render(poseStack, buffers, light, partialTick, level);
    }

    // ---- Snow ----

    /** Little flakes drifting down through the ball, swaying as they fall, and starting over at the top. */
    private static void renderSnow(PoseStack poseStack, MultiBufferSource buffers, int light, float seconds)
    {
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float half = 0.06f / 16f;
        for (float[] seed : FLAKES)
        {
            // Height runs from near the top of the ball down to the ground (about half-way down).
            float fall = (seconds * seed[3] * 0.18f + seed[2]) % 1f;
            float h = 0.85f - fall * 1.3f;
            float spread = (float) Math.sqrt(Math.max(0, 1 - h * h)) * 0.8f;
            float sway = (float) Math.sin(seconds * 1.3f + seed[4]) * 0.06f;
            float x = 0.5f + (seed[0] * spread + sway) * BALL_RADIUS;
            float y = BALL_CENTER_Y + h * BALL_RADIUS;
            float z = 0.5f + (seed[1] * spread) * BALL_RADIUS;
            box(out, pose, x, y, z, half, half, half, light, 255, 255, 255, 255);
        }
    }

    // ---- Storm: clouds, rain and lightning ----

    /** Draws the storm and returns how bright a lightning flash is right now (0 to 1). */
    private static float renderStorm(PoseStack poseStack, MultiBufferSource buffers, int light, float ticks, long seed,
                                     @Nullable ClientGlobeState state, @Nullable ClientLevel level, BlockPos where)
    {
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float seconds = ticks / 20f;

        // Dark clouds hanging under the top of the ball, slowly turning.
        double turn = seconds * 0.05;
        for (float[] c : CLOUDS)
        {
            double angle = Math.atan2(c[1], c[0]) + turn, dist = Math.hypot(c[0], c[1]) * 0.5;
            float h = 0.6f + c[2] * 0.18f;
            float size = (0.5f + c[3] * 0.4f) / 16f;
            box(out, pose, 0.5f + (float) (Math.cos(angle) * dist) * BALL_RADIUS, BALL_CENTER_Y + h * BALL_RADIUS,
                    0.5f + (float) (Math.sin(angle) * dist) * BALL_RADIUS, size, size * 0.6f, size, light, 80, 85, 95, 235);
        }

        // Rain: thin streaks falling fast from the clouds to the sea.
        for (float[] d : DROPS)
        {
            float fall = (seconds * d[3] * 1.4f + d[2]) % 1f;
            float h = 0.58f - fall * 0.85f;
            float spread = (float) Math.sqrt(Math.max(0, 1 - h * h)) * 0.85f;
            box(out, pose, 0.5f + d[0] * spread * BALL_RADIUS, BALL_CENTER_Y + h * BALL_RADIUS, 0.5f + d[1] * spread * BALL_RADIUS,
                    0.035f / 16f, 0.3f / 16f, 0.035f / 16f, light, 175, 195, 225, 170);
        }

        // Lightning: some slots strike, at a random moment in the slot, flickering for a few ticks.
        long tick = (long) Math.floor(ticks);
        long slot = Math.floorDiv(tick, STRIKE_SLOT);
        long hash = mix(slot * 31 + seed);
        if (Math.floorMod(hash, 3) != 0)
            return 0;
        long start = slot * STRIKE_SLOT + Math.floorMod(hash >> 8, STRIKE_SLOT - 10);
        long age = tick - start;
        if (age < 0 || age > 5)
            return 0;
        if (age == 0 && state != null && level != null && state.lastThunder != start)
        {
            // A tiny storm makes a tiny thunderclap: quiet and high-pitched.
            state.lastThunder = start;
            level.playLocalSound(where, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 0.25f, 1.9f, false);
        }
        boolean lit = age == 0 || age == 1 || age == 3;
        if (lit)
            renderBolt(out, pose, hash);
        return lit ? 1 - age / 6f : 0;
    }

    /** A jagged bolt from the clouds down to the sea, its shape fixed per strike. */
    private static void renderBolt(VertexConsumer out, PoseStack.Pose pose, long hash)
    {
        Random random = new Random(hash);
        float x = (random.nextFloat() - 0.5f) * 0.7f, z = (random.nextFloat() - 0.5f) * 0.7f, h = 0.6f;
        float half = 0.012f / 16f * 16f * 0.5f;
        for (int segment = 0; segment < 6; segment++)
        {
            float nx = x + (random.nextFloat() - 0.5f) * 0.25f;
            float nz = z + (random.nextFloat() - 0.5f) * 0.25f;
            float nh = h - 0.14f;
            for (int step = 0; step <= 8; step++)
            {
                float t = step / 8f;
                box(out, pose, 0.5f + (x + (nx - x) * t) * BALL_RADIUS, BALL_CENTER_Y + (h + (nh - h) * t) * BALL_RADIUS,
                        0.5f + (z + (nz - z) * t) * BALL_RADIUS, half, half, half, LightTexture.FULL_BRIGHT, 225, 235, 255, 255);
            }
            x = nx;
            z = nz;
            h = nh;
        }
    }

    private static long mix(long v)
    {
        v = (v ^ (v >>> 33)) * 0xff51afd7ed558ccdL;
        v = (v ^ (v >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return v ^ (v >>> 33);
    }

    // ---- Glass ----

    /** Clear where you look straight through, brighter towards the outline, plus a glint where light reflects. */
    private static int glassAlpha(float nx, float ny, float nz, Vec3 eye, float extra)
    {
        double facing = nx * eye.x + ny * eye.y + nz * eye.z;
        double edge = 1 - Math.abs(facing);
        double opacity = 0.12 + 0.8 * edge * edge * edge + extra;
        if (facing > 0)
        {
            Vec3 half = eye.add(LIGHT).normalize();
            double shine = Math.max(0, nx * half.x + ny * half.y + nz * half.z);
            opacity += Math.pow(shine, 60) * 1.5;
        }
        return (int) (255 * Math.min(1, opacity));
    }

    /** The glass ball, tinted (r, g, b). Drawn in the translucent block sheet, after the miniature, so it doesn't hide it. */
    private static void renderBall(PoseStack poseStack, MultiBufferSource buffers, int light, Vec3 eye, int r, int g, int b, float extra)
    {
        VertexConsumer out = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        TextureAtlasSprite glass = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(GLASS);
        float u = glass.getU(0.5f), v = glass.getV(0.5f);
        PoseStack.Pose pose = poseStack.last();
        float[][] ring = new float[BALL_LONGITUDES + 1][];
        for (int lat = 0; lat < BALL_LATITUDES; lat++)
            for (int lon = 0; lon < BALL_LONGITUDES; lon++)
            {
                float[] a = ballPoint(lat, lon), bb = ballPoint(lat + 1, lon), c = ballPoint(lat + 1, lon + 1), d = ballPoint(lat, lon + 1);
                glassQuad(out, pose, a, bb, c, d, eye, light, u, v, r, g, b, extra);
            }
    }

    /** A point on the ball: x, y, z in the block, then its outward normal. */
    private static float[] ballPoint(int lat, int lon)
    {
        double theta = Math.PI * lat / BALL_LATITUDES, phi = Math.PI * 2 * lon / BALL_LONGITUDES;
        float nx = (float) (Math.sin(theta) * Math.cos(phi)), ny = (float) Math.cos(theta), nz = (float) (Math.sin(theta) * Math.sin(phi));
        return new float[] {0.5f + nx * BALL_RADIUS, BALL_CENTER_Y + ny * BALL_RADIUS, 0.5f + nz * BALL_RADIUS, nx, ny, nz};
    }

    /** The green glass bottle lying on its side: its profile turned round the bottle's axis. */
    private static void renderBottle(PoseStack poseStack, MultiBufferSource buffers, int light, Vec3 eye)
    {
        VertexConsumer out = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        TextureAtlasSprite glass = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(GLASS);
        float u = glass.getU(0.5f), v = glass.getV(0.5f);
        PoseStack.Pose pose = poseStack.last();
        for (int i = 0; i + 1 < BOTTLE_PROFILE.length; i++)
        {
            float x0 = BOTTLE_PROFILE[i][0], r0 = BOTTLE_PROFILE[i][1], x1 = BOTTLE_PROFILE[i + 1][0], r1 = BOTTLE_PROFILE[i + 1][1];
            for (int s = 0; s < BOTTLE_SEGMENTS; s++)
            {
                float[] a = bottlePoint(x0, r0, x1, r1, s, false), b = bottlePoint(x0, r0, x1, r1, s, true);
                float[] c = bottlePoint(x0, r0, x1, r1, s + 1, true), d = bottlePoint(x0, r0, x1, r1, s + 1, false);
                // Green bottle glass, and a bit more of it than the ball: a long cylinder has less outline to catch the eye.
                glassQuad(out, pose, a, b, c, d, eye, light, u, v, 165, 222, 180, 0.14f);
            }
        }
    }

    /** A point on one band of the bottle (at its start or end), with the band's outward normal. */
    private static float[] bottlePoint(float x0, float r0, float x1, float r1, int segment, boolean end)
    {
        double phi = Math.PI * 2 * segment / BOTTLE_SEGMENTS;
        float cos = (float) Math.cos(phi), sin = (float) Math.sin(phi);
        float x = end ? x1 : x0, r = end ? r1 : r0;
        // Normal of a band in the profile: perpendicular to its slope, pointing away from the axis.
        float dx = x1 - x0, dr = r1 - r0, len = (float) Math.sqrt(dx * dx + dr * dr);
        float nx = -dr / len, nr = dx / len;
        return new float[] {x / 16f, BOTTLE_Y + r * cos / 16f, 0.5f + r * sin / 16f, nx, nr * cos, nr * sin};
    }

    /** One glass quad, wound so its front faces outwards (the translucent sheet hides back faces). */
    private static void glassQuad(VertexConsumer out, PoseStack.Pose pose, float[] a, float[] b, float[] c, float[] d, Vec3 eye,
                                  int light, float u, float v, int r, int g, int bl, float extra)
    {
        float ex = b[0] - a[0], ey = b[1] - a[1], ez = b[2] - a[2];
        float fx = c[0] - a[0], fy = c[1] - a[1], fz = c[2] - a[2];
        // Some quads have a corner where two points meet (the ball's poles); use the other diagonal then.
        if (ex * ex + ey * ey + ez * ez < 1e-12f)
        {
            ex = d[0] - a[0];
            ey = d[1] - a[1];
            ez = d[2] - a[2];
            float tx = ex, ty = ey, tz = ez;
            ex = fx;
            ey = fy;
            ez = fz;
            fx = tx;
            fy = ty;
            fz = tz;
        }
        float cx = ey * fz - ez * fy, cy = ez * fx - ex * fz, cz = ex * fy - ey * fx;
        float nx = a[3] + b[3] + c[3] + d[3], ny = a[4] + b[4] + c[4] + d[4], nz = a[5] + b[5] + c[5] + d[5];
        float[][] order = cx * nx + cy * ny + cz * nz >= 0 ? new float[][] {a, b, c, d} : new float[][] {a, d, c, b};
        for (float[] p : order)
            vertex(out, pose, p[0], p[1], p[2], u, v, p[3], p[4], p[5], light,
                    r, g, bl, glassAlpha(p[3], p[4], p[5], eye, extra));
    }

    // ---- Helpers ----

    private static void box(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float hx, float hy, float hz,
                            int light, int r, int g, int b, int a)
    {
        PoseStack.Pose m = pose;
        float x0 = x - hx, x1 = x + hx, y0 = y - hy, y1 = y + hy, z0 = z - hz, z1 = z + hz;
        int[] c = {r, g, b, a};
        quad(out, m, light, c, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(out, m, light, c, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, m, light, c, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(out, m, light, c, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, m, light, c, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, m, light, c, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose m, int light, int[] c, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz)
    {
        vertex(out, m, ax, ay, az, 0, 0, nx, ny, nz, light, c[0], c[1], c[2], c[3]);
        vertex(out, m, bx, by, bz, 0, 1, nx, ny, nz, light, c[0], c[1], c[2], c[3]);
        vertex(out, m, cx, cy, cz, 1, 1, nx, ny, nz, light, c[0], c[1], c[2], c[3]);
        vertex(out, m, dx, dy, dz, 1, 0, nx, ny, nz, light, c[0], c[1], c[2], c[3]);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light, int r, int g, int b, int a)
    {
        out.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }
}
