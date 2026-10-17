package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import com.nick.snowglobe.GlobeBlock;
import com.nick.snowglobe.DisplaySize;
import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.snowglobe.GlobeBlockEntity;
import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSavedData;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.GlobeSpace;
import com.nick.snowglobe.MiniatureLayout;
import static com.nick.snowglobe.MiniatureLayout.*;
import com.nick.snowglobe.SnowGlobeMod;
import com.nick.snowglobe.SnowGlobeConfig;
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

import java.util.ArrayList;
import java.util.List;
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

    private static final int BALL_LATITUDES = 14;
    private static final int BALL_LONGITUDES = 28;


    // Bottle, lying along x on its stand: a profile (x, radius) in pixels, turned round the bottle's axis.
    private static final float[][] BOTTLE_PROFILE = {{1.5f, 0}, {1.5f, 3.85f}, {12f, 3.85f}, {13.2f, 1.5f}, {15f, 1.5f}};
    private static final int BOTTLE_SEGMENTS = 24;

    /** Direction the light comes from, for the glint on the glass. */
    private static final Vec3 LIGHT = new Vec3(0.35, 1, 0.25).normalize();
    /** Items don't know where the viewer is; this matches how they're usually seen (front, slightly above). */
    private static final Vec3 ITEM_EYE = new Vec3(0.6, 0.45, 0.65).normalize();

    private static final float[][] FLAKES = seeds(70, 1225);
    private static final float[][] DROPS = seeds(90, 77);
    private static final float[][] CLOUDS = seeds(10, 4242);
    private static final float[][] STARS = seeds(46, 31337);
    private static final float[][] EMBERS = seeds(40, 666);
    private static final float[][] BUBBLES = seeds(30, 4711);

    // Jar, standing on its base: a profile (y, radius) in pixels, turned round the jar's upright axis.
    private static final float[][] JAR_PROFILE = {{1f, 0}, {1f, 4.75f}, {12f, 4.75f}, {12.5f, 4.25f}};
    /** Ticks between possible lightning strikes; about one in three slots actually strikes. */
    private static final int STRIKE_SLOT = 50;
    /** How long a shake takes to settle, in ticks. */
    private static final float SHAKE_TICKS = 70;

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
        return switch (kind)
        {
            case SHIP_BOTTLE -> new Vec3(BOTTLE_MID_X, BOTTLE_Y, 0.5);
            case NETHER_JAR -> new Vec3(0.5, (JAR_FLOOR + JAR_TOP) / 2, 0.5);
            case TERRARIUM, AQUARIUM -> new Vec3(0.5, 6 / 16.0, 0.5);
            default -> new Vec3(0.5, BALL_CENTER_Y, 0.5);
        };
    }

    /** How high a kind's base reaches, where its glass sits on it (in blocks). */
    private static float baseTop(GlobeKind kind)
    {
        return switch (kind)
        {
            case SHIP_BOTTLE -> 1.5f / 16;
            case NETHER_JAR -> JAR_FLOOR;
            case TERRARIUM, AQUARIUM -> 0;
            default -> BALL_CENTER_Y - BALL_RADIUS;
        };
    }

    /** Light for the miniature: dimmer when it's evening or night inside (the star globe always is). */
    static int insideLight(GlobeKind kind, int phase, int light)
    {
        if (kind == GlobeKind.NETHER_JAR || kind == GlobeKind.AQUARIUM)
            return light;
        int cap = kind == GlobeKind.STAR_GLOBE ? 4 : switch (phase)
        {
            case GlobeSavedData.PHASE_SUNSET -> 10;
            case GlobeSavedData.PHASE_NIGHT -> 4;
            case GlobeSavedData.PHASE_SUNRISE -> 11;
            default -> 15;
        };
        return LightTexture.pack(LightTexture.block(light), Math.min(LightTexture.sky(light), cap));
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
        if (state.lastSnapshot != globe.getSnapshot())
        {
            if (state.lastSnapshot != null)
                MiniatureCache.release(state.lastSnapshot);
            state.lastSnapshot = globe.getSnapshot();
        }

        BlockPos pos = globe.getBlockPos();
        BlockState blockState = globe.getBlockState();
        int turns = blockState.hasProperty(GlobeBlock.FACING) ? GlobeBlock.quarterTurns(blockState.getValue(GlobeBlock.FACING)) : 0;
        int scale = blockState.hasProperty(GlobeBlock.DISPLAY) ? blockState.getValue(GlobeBlock.DISPLAY).scale : 1;
        float turnRadians = (float) Math.toRadians(-90 * turns);

        // Where the camera is, seen from the glass's centre in the globe's own (turned) frame, so the glass
        // can catch the light at its edges.
        Vec3 center = glassCenter(globe.kind()).subtract(0.5, 0, 0.5).scale(scale).yRot(turnRadians).add(0.5, 0, 0.5);
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 eye = camera.subtract(Vec3.atLowerCornerOf(pos).add(center)).normalize().yRot(-turnRadians);

        // A shake: a wobble that dies down over a couple of seconds, and the contents stirred up.
        float shaken = (level.getGameTime() - globe.shakeStart + partialTick) / SHAKE_TICKS;
        float ticks = level.getGameTime() % 240000 + partialTick;
        // On a turntable: a full turn every 24 seconds, base and all (the block model leaves the globe to us then).
        boolean spinning = blockState.hasProperty(GlobeBlock.SPINNING) && blockState.getValue(GlobeBlock.SPINNING);
        if (spinning)
        {
            float angle = ticks * 0.75f % 360;
            poseStack.pushPose();
            poseStack.translate(0.5f, 0, 0.5f);
            poseStack.mulPose(Axis.YP.rotationDegrees(angle));
            poseStack.translate(-0.5f, 0, -0.5f);
            eye = eye.yRot((float) Math.toRadians(angle));
        }
        // The turntable is the base then: the globe leaves its own off and sits on the plate (a glass case keeps its
        // frame, which holds the glass). No base, no name plate.
        boolean ownBase = !spinning || globe.kind() == GlobeKind.TERRARIUM || globe.kind() == GlobeKind.AQUARIUM;
        if (!ownBase)
            poseStack.translate(0, -baseTop(globe.kind()), 0);
        renderPlaced(blockState, globe.kind(), globe.getSnapshot(), poseStack, buffers, light, ticks, blocks, eye, state, level, pos,
                shaken, spinning && ownBase, new Look(globe.phase(), globe.season(), ownBase ? globe.name() : null));
        if (spinning)
            poseStack.popPose();
    }

    /**
     * A globe standing in the world, at the pose's origin: turned the way it faces, drawn small, medium or large,
     * wobbling if it's being shaken ({@code shaken} runs 0 to 1 through a shake). Used for placed globes, and
     * for the globes seen giant from inside another one ({@code base}: draw the base too, as it's not in the world there).
     */
    static void renderPlaced(BlockState blockState, GlobeKind kind, @Nullable GlobeSnapshot snapshot, PoseStack poseStack,
                             MultiBufferSource buffers, int light, float ticks, BlockRenderDispatcher blocks, @Nullable Vec3 eye,
                             @Nullable ClientGlobeState state, ClientLevel level, BlockPos pos, float shaken, boolean base, Look look)
    {
        int turns = blockState.hasProperty(GlobeBlock.FACING) ? GlobeBlock.quarterTurns(blockState.getValue(GlobeBlock.FACING)) : 0;
        int scale = blockState.hasProperty(GlobeBlock.DISPLAY) ? blockState.getValue(GlobeBlock.DISPLAY).scale : 1;
        float stir = shaken >= 0 && shaken < 1 ? (1 - shaken) * (1 - shaken) : 0;

        poseStack.pushPose();
        poseStack.translate(0.5f, 0, 0.5f);
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(Axis.YP.rotationDegrees(-90 * turns));
        poseStack.translate(-0.5f, 0, -0.5f);
        // A small globe's base is part of the world's block model; bigger globes draw theirs here, scaled up.
        // Either way the base stands still when shaken.
        if (scale > 1 || base)
            blocks.renderSingleBlock(blockState.setValue(GlobeBlock.DISPLAY, DisplaySize.SMALL).setValue(GlobeBlock.FACING, Direction.EAST)
                            .setValue(GlobeBlock.SPINNING, false),
                    poseStack, buffers, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        if (look.name() != null)
            renderNamePlate(kind, look.name(), poseStack, buffers, light);
        // Globes, bottles and jars rock on their base, about where they sit on it; a glass case (terrarium,
        // aquarium) is too heavy to, and its glass is part of the block, so the contents would swing out through it.
        boolean rocks = kind != GlobeKind.TERRARIUM && kind != GlobeKind.AQUARIUM;
        if (stir > 0 && rocks)
        {
            float pivot = switch (kind)
            {
                case SHIP_BOTTLE -> 2.5f / 16;
                case NETHER_JAR -> JAR_FLOOR;
                default -> BALL_CENTER_Y - BALL_RADIUS;
            };
            float wobble = (float) Math.sin(shaken * 38) * 9 * stir;
            poseStack.translate(0.5f, pivot, 0.5f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(wobble));
            poseStack.mulPose(Axis.XP.rotationDegrees(wobble * 0.6f));
            poseStack.translate(-0.5f, -pivot, -0.5f);
        }
        renderGlobe(kind, snapshot, poseStack, buffers, light, ticks, blocks, eye, state, pos.asLong(), level, pos, stir, look);
        poseStack.popPose();
    }

    /**
     * A little brass plate on the front of the base with the globe's name engraved on it. In the model's own frame
     * (facing east), the front is the +z side: the side you face when you place it.
     */
    private static void renderNamePlate(GlobeKind kind, net.minecraft.network.chat.Component name, PoseStack poseStack,
                                        MultiBufferSource buffers, int light)
    {
        // Where the base's front face is (in pixels), how high its middle is, and how wide a plate it takes.
        float front, middle, width;
        switch (kind)
        {
            case TERRARIUM, AQUARIUM -> { front = 14; middle = 0.5f; width = 10; }
            case SHIP_BOTTLE -> { front = 12.5f; middle = 1.25f; width = 9; }
            case NETHER_JAR -> { front = 13; middle = 0.5f; width = 8; }
            default -> { front = 12; middle = 1; width = 7; }
        }
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int textWidth = font.width(name);
        // Text 1.1 px tall, made smaller if the name is too long for the plate.
        float scale = Math.min(1.1f / 16 / 8, width * 0.9f / 16 / Math.max(1, textWidth));
        float plateHalf = Math.min(width / 2 / 16, (textWidth * scale) / 2 + 0.4f / 16);
        float plateHeight = Math.min(middle, 0.8f) / 16;
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        box(out, poseStack.last(), 0.5f, middle / 16, front / 16 + 0.1f / 16, plateHalf, plateHeight, 0.1f / 16, light, 205, 160, 70, 255);
        poseStack.pushPose();
        poseStack.translate(0.5f, middle / 16, front / 16 + 0.21f / 16);
        poseStack.scale(scale, -scale, scale);
        font.drawInBatch(name, -textWidth / 2f, -4, 0x3A2810, false, poseStack.last().pose(), buffers,
                net.minecraft.client.gui.Font.DisplayMode.POLYGON_OFFSET, 0, light);
        poseStack.popPose();
    }

    /** What a globe shows besides its blocks: the time of day and season inside. */
    public record Look(int phase, int season, @Nullable net.minecraft.network.chat.Component name)
    {
        public static final Look DEFAULT = new Look(0, 0);

        public Look(int phase, int season)
        {
            this(phase, season, null);
        }

        /** The same, with the globe's name for its plate. */
        public Look named(@Nullable net.minecraft.network.chat.Component name)
        {
            return new Look(phase, season, name);
        }

        /** Read from a globe's saved fields (an item's data, or a neighbour sent to someone inside). */
        public static Look of(net.minecraft.nbt.CompoundTag tag)
        {
            return new Look(tag.getInt("Phase"), tag.getInt("Season"));
        }
    }

    /** Medium and large globes are drawn bigger than their block; keep drawing them while any of that is in view. */
    @Override
    public AABB getRenderBoundingBox(GlobeBlockEntity globe)
    {
        BlockState state = globe.getBlockState();
        int scale = state.hasProperty(GlobeBlock.DISPLAY) ? state.getValue(GlobeBlock.DISPLAY).scale : 1;
        return new AABB(globe.getBlockPos()).inflate(scale - 1, 0, scale - 1).expandTowards(0, scale - 1, 0);
    }

    /**
     * Shared by the placed block and the item, so a globe looks the same in your hand as on a shelf.
     * {@code eye} is the direction to the viewer from the glass's centre, or null when unknown (items);
     * {@code state} holds the globe's mobs, or is null when there are none to show (items).
     */
    public static void renderGlobe(GlobeKind kind, @Nullable GlobeSnapshot snapshot, PoseStack poseStack, MultiBufferSource buffers,
                                   int light, float ticks, BlockRenderDispatcher blocks, @Nullable Vec3 eye,
                                   @Nullable ClientGlobeState state, long seed, @Nullable ClientLevel level, BlockPos where, float stir,
                                   Look look)
    {
        int outsideLight = light;
        light = insideLight(kind, look.phase(), light);
        // Placed globes (which know where you are) queue their glass to draw after the world; items draw it straight away.
        boolean inWorld = eye != null;
        Vec3 view = eye != null ? eye : ITEM_EYE;
        float partialTick = ticks - (float) Math.floor(ticks);
        MiniatureLayout layout = MiniatureLayout.of(kind, snapshot);
        // Far-away placed globes skip what would be too small to see: their mobs, then half and finally all of their
        // weather. Items, and the globes seen giant from inside another, always get everything.
        boolean detailed = true;
        boolean showMobs = true;
        int weatherCount = FLAKES.length;
        if (inWorld && level != null && where != null)
        {
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            BlockState placed = level.getBlockState(where);
            double size = placed.hasProperty(GlobeBlock.DISPLAY) ? placed.getValue(GlobeBlock.DISPLAY).scale : 1;
            double dist = Math.sqrt(cam.distanceToSqr(where.getX() + 0.5, where.getY() + 0.5, where.getZ() + 0.5)) / size;
            detailed = dist < 40;
            showMobs = dist < 24;
            if (dist > 24)
                weatherCount = 0;
            else if (dist > 12)
                weatherCount = FLAKES.length / 2;
        }

        switch (kind)
        {
            case SNOW_GLOBE ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
                if (detailed && weatherCount > 0)
                    renderSnow(poseStack, buffers, outsideLight, ticks / 20f, stir, look.season(), weatherCount);
                renderBall(poseStack, buffers, inWorld, outsideLight, view, 255, 255, 255, 0);
            }
            case DESERT_GLOBE ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
                if (detailed && weatherCount > 0)
                    renderSand(poseStack, buffers, outsideLight, ticks / 20f, stir, weatherCount);
                renderBall(poseStack, buffers, inWorld, outsideLight, view, 255, 236, 205, 0.02f);
            }
            case STAR_GLOBE ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
                if (detailed && weatherCount > 0)
                    renderStars(poseStack, buffers, ticks, seed, stir, weatherCount);
                renderBall(poseStack, buffers, inWorld, outsideLight, view, 200, 212, 255, 0.04f);
            }
            case AQUARIUM ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
                if (detailed && weatherCount > 0)
                    renderBubbles(poseStack, buffers, outsideLight, ticks / 20f, stir, weatherCount);
            }
            case NETHER_JAR ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
                if (detailed && weatherCount > 0)
                    renderEmbers(poseStack, buffers, ticks / 20f, stir, weatherCount);
                renderJar(poseStack, buffers, inWorld, outsideLight, view);
            }
            case STORM_GLOBE ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
                float flash = renderStorm(poseStack, buffers, outsideLight, ticks, seed, state, level, where, stir, detailed && weatherCount > 0);
                int grey = (int) (150 + 105 * flash);
                renderBall(poseStack, buffers, inWorld, outsideLight, view, grey, grey + (int) (10 * (1 - flash)), grey + (int) (30 * (1 - flash)), flash * 0.35f);
            }
            case SHIP_BOTTLE ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
                renderBottle(poseStack, buffers, inWorld, outsideLight, view);
            }
            default ->
            {
                pushMiniature(poseStack, layout);
                renderContents(snapshot, state, level, where, partialTick, poseStack, buffers, light, blocks, detailed, showMobs);
                poseStack.popPose();
            }
        }
    }

    /** Switches to snapshot cell units, with the layout's pivot cell placed at its centre point. */
    private static void pushMiniature(PoseStack poseStack, MiniatureLayout layout)
    {
        poseStack.pushPose();
        poseStack.translate(layout.center().x, layout.center().y, layout.center().z);
        poseStack.scale(layout.cell(), layout.cell(), layout.cell());
        poseStack.translate(-layout.pivot().x, -layout.pivot().y, -layout.pivot().z);
    }

    private static void renderContents(@Nullable GlobeSnapshot snapshot, @Nullable ClientGlobeState state, @Nullable ClientLevel level,
                                       BlockPos where, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light,
                                       BlockRenderDispatcher blocks, boolean detailed, boolean showMobs)
    {
        if (snapshot != null && level != null)
        {
            // Blocks and water come from a mesh built once per snapshot; only block entity renderers draw fresh.
            MiniatureCache cache = MiniatureCache.of(snapshot, level);
            cache.mesh(snapshot, light, level, where, blocks).draw(poseStack.last().pose());
            cache.blockEntities.renderDynamic(poseStack, buffers, light, partialTick);
        }
        if (state != null && level != null && detailed && showMobs)
        {
            // Mob positions are in blocks; a downsampled snapshot has several blocks per cell.
            float perCell = snapshot != null ? 1f / snapshot.scale() : 1f;
            poseStack.pushPose();
            poseStack.scale(perCell, perCell, perCell);
            state.mobs.render(poseStack, buffers, light, partialTick, level);
            poseStack.popPose();
        }
    }

    // ---- Snow ----

    /** Autumn leaf colours: orange, red, gold and brown. */
    private static final int[][] LEAF_COLOURS = {{230, 120, 30}, {200, 50, 30}, {235, 180, 40}, {150, 90, 40}};

    /**
     * What drifts through the snow globe, by season: snowflakes in winter, blossom petals in spring, fireflies in
     * summer and falling leaves in autumn.
     */
    private static void renderSnow(PoseStack poseStack, MultiBufferSource buffers, int light, float seconds, float stir, int season, int count)
    {
        if (count <= 0)
            return;
        if (season == GlobeSavedData.SUMMER)
        {
            renderFireflies(poseStack, buffers, seconds, stir);
            return;
        }
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        boolean leaves = season == GlobeSavedData.AUTUMN, petals = season == GlobeSavedData.SPRING;
        float half = (leaves ? 0.1f : petals ? 0.08f : 0.06f) / 16f;
        // Petals and leaves float down more slowly than snow, and swing further as they fall.
        float speed = leaves ? 0.6f : petals ? 0.75f : 1f, swing = leaves ? 2.2f : petals ? 1.6f : 1f;
        int max = Math.min(count, FLAKES.length);
        for (int i = 0; i < max; i++)
        {
            float[] seed = FLAKES[i];
            if ((leaves || petals) && i % 2 == 1)
                continue;
            int r = 255, g = 255, b = 255;
            if (petals)
            {
                r = 255;
                g = 180 + (int) (seed[4] * 8);
                b = 200 + (int) (seed[2] * 30);
            }
            else if (leaves)
            {
                int[] c = LEAF_COLOURS[i / 2 % LEAF_COLOURS.length];
                r = c[0];
                g = c[1];
                b = c[2];
            }
            float fall = (seconds * seed[3] * 0.18f * speed + seed[2]) % 1f;
            float h = 0.85f - fall * 1.3f;
            float spread = (float) Math.sqrt(Math.max(0, 1 - h * h)) * 0.8f;
            float sway = (float) Math.sin(seconds * 1.3f + seed[4]) * 0.06f * swing;
            // Shaken: thrown up through the whole ball, whirling round, then settling back as the stir fades.
            h += stir * ((0.75f - seed[2] * 1.3f) - h);
            spread += stir * (0.85f * (float) Math.sqrt(Math.max(0, 1 - h * h)) - spread);
            double whirl = stir * seconds * 7;
            float sx = (float) (seed[0] * Math.cos(whirl) - seed[1] * Math.sin(whirl));
            float sz = (float) (seed[0] * Math.sin(whirl) + seed[1] * Math.cos(whirl));
            float x = 0.5f + (sx * spread + sway) * BALL_RADIUS;
            float y = BALL_CENTER_Y + h * BALL_RADIUS;
            float z = 0.5f + (sz * spread) * BALL_RADIUS;
            if (leaves || petals)
                crossQuad(out, pose, x, y, z, half, half * 0.3f, half, light, r, g, b, 255);
            else
                crossQuad(out, pose, x, y, z, half, half, half, light, r, g, b, 255);
        }
    }

    /** Summer in the snow globe: fireflies blinking as they wander over the ground. */
    private static void renderFireflies(PoseStack poseStack, MultiBufferSource buffers, float seconds, float stir)
    {
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float half = 0.045f / 16f;
        for (int i = 0; i < 24; i++)
        {
            float[] seed = FLAKES[i];
            float t = seconds * (0.15f + seed[3] * 0.1f) * (1 + stir * 6) + seed[4];
            float x = 0.5f + (seed[0] * 0.6f + (float) Math.sin(t * 1.7f) * 0.15f) * BALL_RADIUS;
            float z = 0.5f + (seed[1] * 0.6f + (float) Math.cos(t * 1.3f) * 0.15f) * BALL_RADIUS;
            float y = BALL_CENTER_Y + (-0.15f + seed[2] * 0.45f + (float) Math.sin(t * 2.1f) * 0.06f + stir * 0.3f) * BALL_RADIUS;
            float blink = (float) Math.sin(seconds * (1.5f + seed[3]) + seed[4] * 3);
            if (blink < 0.2f)
                continue;
            box(out, pose, x, y, z, half, half, half, LightTexture.FULL_BRIGHT, 220, 255, 110, (int) (255 * blink), true);
        }
    }

    // ---- Desert: sand blown across the dunes ----

    private static void renderSand(PoseStack poseStack, MultiBufferSource buffers, int light, float seconds, float stir, int count)
    {
        if (count <= 0)
            return;
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float half = 0.045f / 16f;
        int max = Math.min(count, DROPS.length);
        for (int i = 0; i < max; i++)
        {
            float[] seed = DROPS[i];
            // Each grain crosses the ball on the wind, low over the ground, and starts over on the other side.
            float u = (seconds * seed[3] * (0.22f + stir * 1.5f) + seed[2]) % 1f;
            float x = -0.95f + u * 1.9f;
            float h = -0.45f + (seed[1] + 1) * 0.12f + (float) Math.sin(seconds * 2 + seed[4]) * 0.04f;
            h += stir * ((seed[1] + 1) * 0.55f);
            float z = seed[0] * 0.7f;
            if (x * x + h * h + z * z > 0.8f)
                continue;
            crossQuad(out, pose, 0.5f + x * BALL_RADIUS, BALL_CENTER_Y + h * BALL_RADIUS, 0.5f + z * BALL_RADIUS,
                    half, half, half, light, 222, 196, 140, 230);
        }
    }

    // ---- Stars: a night sky inside the ball, with shooting stars and the northern lights ----

    private static void renderStars(PoseStack poseStack, MultiBufferSource buffers, float ticks, long seed, float stir, int weatherCount)
    {
        if (weatherCount <= 0)
            return;
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float seconds = ticks / 20f;
        float half = 0.035f / 16f;
        int count = Math.min(STARS.length, weatherCount * STARS.length / FLAKES.length);
        for (int i = 0; i < count; i++)
        {
            float[] s = STARS[i];
            double angle = Math.atan2(s[1], s[0]);
            float y = 0.3f + 0.62f * s[2], r = (float) Math.sqrt(Math.max(0, 1 - y * y)) * 0.9f;
            float twinkle = 0.55f + 0.45f * (float) Math.sin(seconds * (2 + s[3] * 3) + s[4] * 5);
            crossQuad(out, pose, 0.5f + (float) Math.cos(angle) * r * BALL_RADIUS, BALL_CENTER_Y + y * 0.9f * BALL_RADIUS,
                    0.5f + (float) Math.sin(angle) * r * BALL_RADIUS, half, half, half, LightTexture.FULL_BRIGHT,
                    235, 240, 255, (int) (255 * twinkle));
        }
        // The northern lights: a wavering green curtain across the top of the ball.
        for (int i = 0; i < 26; i++)
        {
            float t = i / 25f;
            float x = -0.7f + t * 1.4f;
            float z = -0.35f + (float) Math.sin(t * 4 + seconds * 0.4f) * 0.2f;
            float y = 0.45f + (float) Math.sin(t * 7 + seconds * 0.9f) * 0.05f;
            if (x * x + y * y + z * z > 0.8f)
                continue;
            int alpha = (int) (70 + 50 * Math.sin(t * 9 + seconds * 1.3f));
            box(out, pose, 0.5f + x * BALL_RADIUS, BALL_CENTER_Y + y * BALL_RADIUS, 0.5f + z * BALL_RADIUS,
                    0.03f / 16f, 0.55f / 16f, 0.03f / 16f, LightTexture.FULL_BRIGHT, 80, 255, 160 + i * 3, alpha, true);
        }
        // Shooting stars: now and then one streaks across; shaking the globe lets loose a shower.
        int streaks = stir > 0 ? 4 : 1;
        for (int n = 0; n < streaks; n++)
        {
            long slot = (long) Math.floor(ticks / 60) * 7 + n;
            long hash = mix(slot + seed);
            if (stir <= 0 && Math.floorMod(hash, 3) != 0)
                continue;
            float age = (ticks % 60) / 60f * (stir > 0 ? 3 : 1) - n * 0.2f;
            if (age < 0 || age > 0.35f)
                continue;
            Random random = new Random(hash);
            float sx = -0.6f + random.nextFloat() * 0.4f, sy = 0.5f + random.nextFloat() * 0.25f, sz = (random.nextFloat() - 0.5f) * 0.8f;
            for (int k = 0; k < 6; k++)
            {
                float p = age / 0.35f - k * 0.04f;
                if (p < 0)
                    continue;
                float x = sx + p * 1.1f, y = sy - p * 0.35f;
                box(out, pose, 0.5f + x * BALL_RADIUS, BALL_CENTER_Y + y * BALL_RADIUS, 0.5f + sz * BALL_RADIUS,
                        half, half, half, LightTexture.FULL_BRIGHT, 255, 250, 220, 255 - k * 40, true);
            }
        }
    }

    // ---- Aquarium: bubbles rising through the water ----

    private static void renderBubbles(PoseStack poseStack, MultiBufferSource buffers, int light, float seconds, float stir, int weatherCount)
    {
        if (weatherCount <= 0)
            return;
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float height = 9f / 16f;
        int count = Math.min(BUBBLES.length, weatherCount * BUBBLES.length / FLAKES.length);
        for (int i = 0; i < count; i++)
        {
            float[] s = BUBBLES[i];
            float rise = (seconds * s[3] * (0.25f + stir * 1.2f) + s[2]) % 1f;
            float half = (0.035f + s[2] * 0.03f) / 16f;
            float x = 0.5f + s[0] * CASE_WIDTH * 0.42f + (float) Math.sin(seconds * 3 + s[4]) * 0.004f;
            float z = 0.5f + s[1] * CASE_WIDTH * 0.42f;
            crossQuad(out, pose, x, CASE_FLOOR + 0.1f / 16f + rise * height, z, half, half, half, light, 225, 245, 255, 150);
        }
    }

    // ---- Nether jar: embers rising from the lava, and the jar's glass ----

    private static void renderEmbers(PoseStack poseStack, MultiBufferSource buffers, float seconds, float stir, int weatherCount)
    {
        if (weatherCount <= 0)
            return;
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float half = 0.035f / 16f;
        float height = JAR_TOP - JAR_FLOOR - 0.5f / 16f;
        int count = Math.min(EMBERS.length, weatherCount * EMBERS.length / FLAKES.length);
        for (int i = 0; i < count; i++)
        {
            float[] s = EMBERS[i];
            float t = (seconds * s[3] * (0.12f + stir * 0.8f) + s[2]) % 1f;
            float swirl = stir * seconds * 5 + s[4];
            float r = (float) Math.hypot(s[0], s[1]) * JAR_INNER * 0.85f;
            double angle = Math.atan2(s[1], s[0]) + swirl * 0.3;
            float x = 0.5f + (float) Math.cos(angle) * r + (float) Math.sin(seconds * 2 + s[4]) * 0.005f;
            float z = 0.5f + (float) Math.sin(angle) * r;
            int alpha = (int) (255 * (1 - t));
            crossQuad(out, pose, x, JAR_FLOOR + t * height, z, half, half, half, LightTexture.FULL_BRIGHT,
                    255, 120 + (int) (s[2] * 90), 40, alpha);
        }
    }

    /** The jar's glass: its profile turned round the upright axis, faintly warm from the fire inside. */
    private static void renderJar(PoseStack poseStack, MultiBufferSource buffers, boolean inWorld, int light, Vec3 eye)
    {
        TextureAtlasSprite glass = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(GLASS);
        float u = glass.getU(0.5f), v = glass.getV(0.5f);
        Vec3 half = eye.add(LIGHT).normalize();
        double eyeX = eye.x, eyeY = eye.y, eyeZ = eye.z;
        double halfX = half.x, halfY = half.y, halfZ = half.z;
        GlassRenderer.draw(poseStack.last(), buffers, inWorld, (out, pose) -> {
            for (GlassQuad q : JAR_QUADS)
                drawGlassQuad(out, pose, q, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, LightTexture.FULL_BRIGHT, u, v, 255, 214, 185, 0.1f);
        });
    }

    /** A point on one band of the jar (at its start or end), with the band's outward normal. */
    private static float[] jarPoint(float y0, float r0, float y1, float r1, int segment, boolean end)
    {
        double phi = Math.PI * 2 * segment / BOTTLE_SEGMENTS;
        float cos = (float) Math.cos(phi), sin = (float) Math.sin(phi);
        float y = end ? y1 : y0, r = end ? r1 : r0;
        float dy = y1 - y0, dr = r1 - r0, len = (float) Math.sqrt(dy * dy + dr * dr);
        float ny = -dr / len, nr = dy / len;
        return new float[] {0.5f + r * cos / 16f, y / 16f, 0.5f + r * sin / 16f, nr * cos, ny, nr * sin};
    }

    // ---- Storm: clouds, rain and lightning ----

    /** Draws the storm and returns how bright a lightning flash is right now (0 to 1). */
    private static float renderStorm(PoseStack poseStack, MultiBufferSource buffers, int light, float ticks, long seed,
                                     @Nullable ClientGlobeState state, @Nullable ClientLevel level, BlockPos where, float stir, boolean detailed)
    {
        VertexConsumer out = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float seconds = ticks / 20f;

        if (detailed)
        {
            // Dark clouds hanging under the top of the ball, slowly turning; each kept clear of the glass, corners and all.
            double turn = seconds * 0.05;
            for (float[] c : CLOUDS)
            {
                float h = 0.5f + c[2] * 0.14f;
                float size = (0.5f + c[3] * 0.4f) / 16f;
                float s = size / BALL_RADIUS;
                double top = h + s * 0.6f, room = Math.sqrt(Math.max(0, 1 - top * top)) - s * 1.42 - 0.03;
                double angle = Math.atan2(c[1], c[0]) + turn, dist = Math.min(Math.hypot(c[0], c[1]) * 0.5, Math.max(0, room));
                box(out, pose, 0.5f + (float) (Math.cos(angle) * dist) * BALL_RADIUS, BALL_CENTER_Y + h * BALL_RADIUS,
                        0.5f + (float) (Math.sin(angle) * dist) * BALL_RADIUS, size, size * 0.6f, size, light, 90, 95, 105, 115);
            }

            // Rain: thin streaks falling fast from the clouds to the sea.
            for (float[] d : DROPS)
            {
                float fall = (seconds * d[3] * 1.4f + d[2]) % 1f;
                float h = 0.58f - fall * 0.85f;
                float spread = (float) Math.sqrt(Math.max(0, 1 - h * h)) * 0.85f;
                crossQuad(out, pose, 0.5f + d[0] * spread * BALL_RADIUS, BALL_CENTER_Y + h * BALL_RADIUS, 0.5f + d[1] * spread * BALL_RADIUS,
                        0.035f / 16f, 0.3f / 16f, 0.035f / 16f, light, 175, 195, 225, 170);
            }
        }

        // Shaken: a strike right away, flickering as the shake starts.
        if (stir > 0.6f)
        {
            boolean lit = stir > 0.85f || (stir > 0.68f && stir < 0.75f);
            if (lit)
                renderBolt(out, pose, mix(seed * 7 + 1));
            return lit ? 1 : 0;
        }

        // Lightning: some slots strike, at a random moment in the slot, flickering for a few ticks.
        long tick = (long) Math.floor(ticks);
        long start = strikeStart(tick, seed);
        if (start == Long.MIN_VALUE)
            return 0;
        long hash = mix(Math.floorDiv(tick, STRIKE_SLOT) * 31 + seed);
        long age = tick - start;
        if (age < 0 || age > 5)
            return 0;
        if (age == 0 && state != null && level != null && state.lastThunder != start)
        {
            // A tiny storm makes a tiny thunderclap: quiet and high-pitched.
            state.lastThunder = start;
            GlobeBlock.tinyThunder(level, where);
        }
        boolean lit = age == 0 || age == 1 || age == 3;
        if (lit)
            renderBolt(out, pose, hash);
        return lit ? 1 - age / 6f : 0;
    }

    /**
     * When the storm globe with this seed (its block position) strikes in the time slot holding {@code tick}, or
     * Long.MIN_VALUE if that slot stays quiet. Shared by the miniature and the storm inside, so they strike together.
     */
    static long strikeStart(long tick, long seed)
    {
        long slot = Math.floorDiv(tick, STRIKE_SLOT);
        long hash = mix(slot * 31 + seed);
        int rarity = SnowGlobeConfig.lightningRarity();
        if (rarity == 0 || Math.floorMod(hash, rarity) != 0)
            return Long.MIN_VALUE;
        return slot * STRIKE_SLOT + Math.floorMod(hash >> 8, STRIKE_SLOT - 10);
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
                        0.5f + (z + (nz - z) * t) * BALL_RADIUS, half, half, half, LightTexture.FULL_BRIGHT, 225, 235, 255, 255, true);
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

    private record GlassVertex(float x, float y, float z, float nx, float ny, float nz, int index) {}
    private record GlassQuad(GlassVertex v0, GlassVertex v1, GlassVertex v2, GlassVertex v3) {}

    private static GlassVertex[] buildBallVertices()
    {
        GlassVertex[] vertices = new GlassVertex[(BALL_LATITUDES + 1) * BALL_LONGITUDES];
        for (int lat = 0; lat <= BALL_LATITUDES; lat++)
        {
            double theta = Math.PI * lat / BALL_LATITUDES;
            float sinTheta = (float) Math.sin(theta);
            float cosTheta = (float) Math.cos(theta);
            for (int lon = 0; lon < BALL_LONGITUDES; lon++)
            {
                double phi = Math.PI * 2 * lon / BALL_LONGITUDES;
                float nx = (float) (sinTheta * Math.cos(phi));
                float ny = cosTheta;
                float nz = (float) (sinTheta * Math.sin(phi));
                int idx = lat * BALL_LONGITUDES + lon;
                vertices[idx] = new GlassVertex(
                        0.5f + nx * BALL_RADIUS,
                        BALL_CENTER_Y + ny * BALL_RADIUS,
                        0.5f + nz * BALL_RADIUS,
                        nx, ny, nz, idx
                );
            }
        }
        return vertices;
    }

    private static final GlassVertex[] BALL_VERTICES = buildBallVertices();
    private static final int[] BALL_ALPHAS = new int[BALL_VERTICES.length];

    private static List<GlassQuad> buildBallQuads()
    {
        List<GlassQuad> list = new ArrayList<>(BALL_LATITUDES * BALL_LONGITUDES);
        for (int lat = 0; lat < BALL_LATITUDES; lat++)
            for (int lon = 0; lon < BALL_LONGITUDES; lon++)
            {
                int nextLon = (lon + 1) % BALL_LONGITUDES;
                GlassVertex a = BALL_VERTICES[lat * BALL_LONGITUDES + lon];
                GlassVertex b = BALL_VERTICES[(lat + 1) * BALL_LONGITUDES + lon];
                GlassVertex c = BALL_VERTICES[(lat + 1) * BALL_LONGITUDES + nextLon];
                GlassVertex d = BALL_VERTICES[lat * BALL_LONGITUDES + nextLon];

                float ex = b.x - a.x, ey = b.y - a.y, ez = b.z - a.z;
                float fx = c.x - a.x, fy = c.y - a.y, fz = c.z - a.z;
                if (ex * ex + ey * ey + ez * ez < 1e-12f)
                {
                    ex = d.x - a.x;
                    ey = d.y - a.y;
                    ez = d.z - a.z;
                    float tx = ex, ty = ey, tz = ez;
                    ex = fx;
                    ey = fy;
                    ez = fz;
                    fx = tx;
                    fy = ty;
                    fz = tz;
                }
                float cx = ey * fz - ez * fy, cy = ez * fx - ex * fz, cz = ex * fy - ey * fx;
                float nx = a.nx + b.nx + c.nx + d.nx, ny = a.ny + b.ny + c.ny + d.ny, nz = a.nz + b.nz + c.nz + d.nz;
                if (cx * nx + cy * ny + cz * nz >= 0)
                    list.add(new GlassQuad(a, b, c, d));
                else
                    list.add(new GlassQuad(a, d, c, b));
            }
        return List.copyOf(list);
    }

    private static GlassQuad buildGlassQuad(float[] a, float[] b, float[] c, float[] d)
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
        return new GlassQuad(
                new GlassVertex(order[0][0], order[0][1], order[0][2], order[0][3], order[0][4], order[0][5], -1),
                new GlassVertex(order[1][0], order[1][1], order[1][2], order[1][3], order[1][4], order[1][5], -1),
                new GlassVertex(order[2][0], order[2][1], order[2][2], order[2][3], order[2][4], order[2][5], -1),
                new GlassVertex(order[3][0], order[3][1], order[3][2], order[3][3], order[3][4], order[3][5], -1)
        );
    }

    private static List<GlassQuad> buildBottleQuads()
    {
        List<GlassQuad> list = new ArrayList<>();
        for (int i = 0; i + 1 < BOTTLE_PROFILE.length; i++)
        {
            float x0 = BOTTLE_PROFILE[i][0], r0 = BOTTLE_PROFILE[i][1], x1 = BOTTLE_PROFILE[i + 1][0], r1 = BOTTLE_PROFILE[i + 1][1];
            for (int s = 0; s < BOTTLE_SEGMENTS; s++)
            {
                float[] a = bottlePoint(x0, r0, x1, r1, s, false), b = bottlePoint(x0, r0, x1, r1, s, true);
                float[] c = bottlePoint(x0, r0, x1, r1, s + 1, true), d = bottlePoint(x0, r0, x1, r1, s + 1, false);
                list.add(buildGlassQuad(a, b, c, d));
            }
        }
        return List.copyOf(list);
    }

    private static List<GlassQuad> buildJarQuads()
    {
        List<GlassQuad> list = new ArrayList<>();
        for (int i = 0; i + 1 < JAR_PROFILE.length; i++)
        {
            float y0 = JAR_PROFILE[i][0], r0 = JAR_PROFILE[i][1], y1 = JAR_PROFILE[i + 1][0], r1 = JAR_PROFILE[i + 1][1];
            for (int s = 0; s < BOTTLE_SEGMENTS; s++)
            {
                float[] a = jarPoint(y0, r0, y1, r1, s, false), b = jarPoint(y0, r0, y1, r1, s, true);
                float[] c = jarPoint(y0, r0, y1, r1, s + 1, true), d = jarPoint(y0, r0, y1, r1, s + 1, false);
                list.add(buildGlassQuad(a, b, c, d));
            }
        }
        return List.copyOf(list);
    }

    private static final List<GlassQuad> BALL_QUADS = buildBallQuads();
    private static final List<GlassQuad> BOTTLE_QUADS = buildBottleQuads();
    private static final List<GlassQuad> JAR_QUADS = buildJarQuads();

    /** Clear where you look straight through, brighter towards the outline, plus a glint where light reflects. */
    private static int glassAlpha(float nx, float ny, float nz, double eyeX, double eyeY, double eyeZ,
                                  double halfX, double halfY, double halfZ, float extra)
    {
        double facing = nx * eyeX + ny * eyeY + nz * eyeZ;
        double edge = 1 - Math.abs(facing);
        double opacity = 0.12 + 0.8 * edge * edge * edge + extra;
        if (facing > 0)
        {
            double shine = nx * halfX + ny * halfY + nz * halfZ;
            // Below 0.85, shine^60 * 1.5 * 255 rounds to 0. Above 0.85, use repeated squaring instead of Math.pow.
            if (shine > 0.85)
            {
                double s2 = shine * shine;
                double s4 = s2 * s2;
                double s8 = s4 * s4;
                double s16 = s8 * s8;
                double s32 = s16 * s16;
                double s60 = s32 * s16 * s8 * s4;
                opacity += s60 * 1.5;
            }
        }
        return (int) (255 * Math.min(1, opacity));
    }

    private static void drawGlassQuad(VertexConsumer out, PoseStack.Pose pose, GlassQuad q,
                                      double eyeX, double eyeY, double eyeZ,
                                      double halfX, double halfY, double halfZ,
                                      int light, float u, float v, int r, int g, int bl, float extra)
    {
        renderGlassVertex(out, pose, q.v0, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, light, u, v, r, g, bl, extra);
        renderGlassVertex(out, pose, q.v1, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, light, u, v, r, g, bl, extra);
        renderGlassVertex(out, pose, q.v2, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, light, u, v, r, g, bl, extra);
        renderGlassVertex(out, pose, q.v3, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, light, u, v, r, g, bl, extra);
    }

    private static void renderGlassVertex(VertexConsumer out, PoseStack.Pose pose, GlassVertex v,
                                          double eyeX, double eyeY, double eyeZ,
                                          double halfX, double halfY, double halfZ,
                                          int light, float u, float texV, int r, int g, int bl, float extra)
    {
        int a = glassAlpha(v.nx, v.ny, v.nz, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, extra);
        out.addVertex(pose, v.x, v.y, v.z).setColor(r, g, bl, a)
                .setUv(u, texV).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, v.nx, v.ny, v.nz);
    }

    /** The glass ball, tinted (r, g, b). */
    private static void renderBall(PoseStack poseStack, MultiBufferSource buffers, boolean inWorld, int light, Vec3 eye,
                                   int r, int g, int b, float extra)
    {
        TextureAtlasSprite glass = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(GLASS);
        float u = glass.getU(0.5f), v = glass.getV(0.5f);
        Vec3 half = eye.add(LIGHT).normalize();
        double eyeX = eye.x, eyeY = eye.y, eyeZ = eye.z;
        double halfX = half.x, halfY = half.y, halfZ = half.z;
        GlassRenderer.draw(poseStack.last(), buffers, inWorld, (out, pose) -> {
            for (int i = 0; i < BALL_VERTICES.length; i++)
            {
                GlassVertex vert = BALL_VERTICES[i];
                BALL_ALPHAS[i] = glassAlpha(vert.nx, vert.ny, vert.nz, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, extra);
            }
            for (GlassQuad q : BALL_QUADS)
            {
                GlassVertex v0 = q.v0, v1 = q.v1, v2 = q.v2, v3 = q.v3;
                out.addVertex(pose, v0.x, v0.y, v0.z).setColor(r, g, b, BALL_ALPHAS[v0.index])
                        .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, v0.nx, v0.ny, v0.nz);
                out.addVertex(pose, v1.x, v1.y, v1.z).setColor(r, g, b, BALL_ALPHAS[v1.index])
                        .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, v1.nx, v1.ny, v1.nz);
                out.addVertex(pose, v2.x, v2.y, v2.z).setColor(r, g, b, BALL_ALPHAS[v2.index])
                        .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, v2.nx, v2.ny, v2.nz);
                out.addVertex(pose, v3.x, v3.y, v3.z).setColor(r, g, b, BALL_ALPHAS[v3.index])
                        .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, v3.nx, v3.ny, v3.nz);
            }
        });
    }

    /** The green glass bottle lying on its side: its profile turned round the bottle's axis. */
    private static void renderBottle(PoseStack poseStack, MultiBufferSource buffers, boolean inWorld, int light, Vec3 eye)
    {
        TextureAtlasSprite glass = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(GLASS);
        float u = glass.getU(0.5f), v = glass.getV(0.5f);
        Vec3 half = eye.add(LIGHT).normalize();
        double eyeX = eye.x, eyeY = eye.y, eyeZ = eye.z;
        double halfX = half.x, halfY = half.y, halfZ = half.z;
        GlassRenderer.draw(poseStack.last(), buffers, inWorld, (out, pose) -> {
            for (GlassQuad q : BOTTLE_QUADS)
                drawGlassQuad(out, pose, q, eyeX, eyeY, eyeZ, halfX, halfY, halfZ, light, u, v, 165, 222, 180, 0.14f);
        });
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

    // ---- Helpers ----

    private static void box(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float hx, float hy, float hz,
                            int light, int r, int g, int b, int a)
    {
        box(out, pose, x, y, z, hx, hy, hz, light, r, g, b, a, false);
    }

    /** A little box; {@code even}: every side lit like the top (snow and rain shouldn't have dark sides). */
    private static void box(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float hx, float hy, float hz,
                            int light, int r, int g, int b, int a, boolean even)
    {
        PoseStack.Pose m = pose;
        float x0 = x - hx, x1 = x + hx, y0 = y - hy, y1 = y + hy, z0 = z - hz, z1 = z + hz;
        quad(out, m, light, r, g, b, a, even ? 0 : 0, even ? 1 : 1, even ? 0 : 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(out, m, light, r, g, b, a, even ? 0 : 0, even ? 1 : -1, even ? 0 : 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, m, light, r, g, b, a, even ? 0 : 0, even ? 1 : 0, even ? 0 : -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(out, m, light, r, g, b, a, even ? 0 : 0, even ? 1 : 0, even ? 0 : 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, m, light, r, g, b, a, even ? 0 : -1, even ? 1 : 0, even ? 0 : 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, m, light, r, g, b, a, even ? 0 : 1, even ? 1 : 0, even ? 0 : 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    /** Two crossed vertical quads: 8 vertices instead of 24 for weather particles and sparks. */
    private static void crossQuad(VertexConsumer out, PoseStack.Pose m, float x, float y, float z, float hx, float hy, float hz,
                                  int light, int r, int g, int b, int a)
    {
        float x0 = x - hx, x1 = x + hx, y0 = y - hy, y1 = y + hy, z0 = z - hz, z1 = z + hz;
        quad(out, m, light, r, g, b, a, 0, 1, 0, x0, y1, z, x1, y1, z, x1, y0, z, x0, y0, z);
        quad(out, m, light, r, g, b, a, 0, 1, 0, x, y1, z0, x, y1, z1, x, y0, z1, x, y0, z0);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose m, int light, int r, int g, int b, int a, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz)
    {
        vertex(out, m, ax, ay, az, 0, 0, nx, ny, nz, light, r, g, b, a);
        vertex(out, m, bx, by, bz, 0, 1, nx, ny, nz, light, r, g, b, a);
        vertex(out, m, cx, cy, cz, 1, 1, nx, ny, nz, light, r, g, b, a);
        vertex(out, m, dx, dy, dz, 1, 0, nx, ny, nz, light, r, g, b, a);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light, int r, int g, int b, int a)
    {
        out.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }
}
