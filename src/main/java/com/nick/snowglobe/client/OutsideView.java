package com.nick.snowglobe.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nick.snowglobe.GlobeBlock;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.GlobeSpace;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Inside a globe: the real world around the globe block, drawn giant around the build space at exactly the
 * scale the miniature is shown at outside (turned around), with the players near the globe as giants peering in.
 */
@EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT)
public final class OutsideView
{
    /** The latest view, or null when not inside a globe. */
    @Nullable
    private static View view;
    private static final Map<UUID, Giant> giants = new HashMap<>();
    /** The animals and monsters around the globe outside, as giant stand-ins (positions relative to the globe block). */
    private static MiniEntities mobs = new MiniEntities();
    /** The giant world's blocks, built once into a mesh (positions relative to the view's origin). */
    @Nullable
    private static BakedMesh worldMesh;

    private static void dropWorldMesh()
    {
        if (worldMesh != null)
        {
            worldMesh.close();
            worldMesh = null;
        }
    }

    private OutsideView() {}

    /** The world around one globe, as received, and how it maps onto the build space. */
    private record View(BlockPos globePos, int kind, int phase, int season, int spaceSize, int turns, int display, BlockPos interiorMin, float cell, int scale,
                        Vec3 center, Vec3 pivot, BlockPos origin, int sx, int sy, int sz,
                        BlockState[] states, byte[] light, List<BlockPos> visible, List<Neighbour> globes) {}

    /** Another globe standing near the one you're in, with its contents, to draw giant with its miniature. */
    private record Neighbour(BlockPos pos, GlobeSnapshot snapshot, GlobeRenderer.Look look) {}

    static void receive(CompoundTag tag)
    {
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++)
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i));
        int[] size = tag.getIntArray("Size");
        int[] cells = tag.getIntArray("Cells");
        if (size.length != 3 || cells.length != size[0] * size[1] * size[2])
            return;
        BlockState[] states = new BlockState[cells.length];
        for (int i = 0; i < cells.length; i++)
            states[i] = palette[Mth.clamp(cells[i], 0, palette.length - 1)];

        // Only blocks with a face open to the air are worth drawing.
        List<BlockPos> visible = new ArrayList<>();
        BlockPos origin = BlockPos.of(tag.getLong("Origin"));
        for (int y = 0; y < size[1]; y++)
            for (int z = 0; z < size[2]; z++)
                for (int x = 0; x < size[0]; x++)
                {
                    BlockState state = states[(y * size[2] + z) * size[0] + x];
                    if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE && state.getFluidState().isEmpty())
                        continue;
                    if (!enclosed(states, size, x, y, z))
                        visible.add(origin.offset(x, y, z));
                }

        List<Neighbour> globes = new ArrayList<>();
        ListTag globesTag = tag.getList("Globes", Tag.TAG_COMPOUND);
        for (int i = 0; i < globesTag.size(); i++)
        {
            GlobeSnapshot snapshot = GlobeSnapshot.fromTag(globesTag.getCompound(i).getCompound("Snapshot"));
            if (snapshot != null)
                globes.add(new Neighbour(BlockPos.of(globesTag.getCompound(i).getLong("Pos")), snapshot,
                        GlobeRenderer.Look.of(globesTag.getCompound(i))));
        }

        // The same world as before (it's resent every few seconds): keep the mesh.
        byte[] light = tag.getByteArray("Light");
        if (view == null || !view.origin().equals(origin) || !java.util.Arrays.equals(view.states(), states) || !java.util.Arrays.equals(view.light(), light))
            dropWorldMesh();

        view = new View(BlockPos.of(tag.getLong("GlobePos")), tag.getInt("Kind"), tag.getInt("Phase"), tag.getInt("Season"), tag.getInt("SpaceSize"),
                tag.getInt("Turns"), Math.max(1, tag.getInt("Display")),
                BlockPos.of(tag.getLong("InteriorMin")), tag.getFloat("Cell"), Math.max(1, tag.getInt("Scale")),
                new Vec3(tag.getDouble("CenterX"), tag.getDouble("CenterY"), tag.getDouble("CenterZ")),
                new Vec3(tag.getDouble("PivotX"), tag.getDouble("PivotY"), tag.getDouble("PivotZ")),
                origin, size[0], size[1], size[2], states, light, visible, globes);
    }

    private static boolean enclosed(BlockState[] states, int[] size, int x, int y, int z)
    {
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : sides)
        {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= size[0] || ny >= size[1] || nz >= size[2])
                return false;
            if (!states[(ny * size[2] + nz) * size[0] + nx].isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                return false;
        }
        return true;
    }

    // ---- Giant players ----

    /** A player near the globe outside, drawn giant: a stand-in copy that glides between updates. */
    private static final class Giant
    {
        final RemotePlayer player;
        double px, py, pz, cx, cy, cz;
        float pYaw, cYaw, pPitch, cPitch, pHead, cHead, pBody, cBody;
        long updatedAt;

        Giant(RemotePlayer player)
        {
            this.player = player;
        }
    }

    static void receivePlayers(CompoundTag tag)
    {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return;
        long now = level.getGameTime();
        Set<UUID> seen = new HashSet<>();
        ListTag list = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag p = list.getCompound(i);
            UUID id = p.getUUID("Id");
            Giant giant = giants.get(id);
            boolean fresh = giant == null;
            if (fresh)
            {
                // Their real profile (with skin) if they're in the player list, otherwise just a name.
                PlayerInfo info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(id);
                GameProfile profile = info != null ? info.getProfile() : new GameProfile(id, p.getString("Name"));
                giant = new Giant(new RemotePlayer(level, profile));
                giants.put(id, giant);
            }
            float t = fresh ? 1 : progress(giant, now, 0);
            giant.px = fresh ? p.getDouble("X") : Mth.lerp(t, giant.px, giant.cx);
            giant.py = fresh ? p.getDouble("Y") : Mth.lerp(t, giant.py, giant.cy);
            giant.pz = fresh ? p.getDouble("Z") : Mth.lerp(t, giant.pz, giant.cz);
            giant.pYaw = fresh ? p.getFloat("Yaw") : Mth.rotLerp(t, giant.pYaw, giant.cYaw);
            giant.pPitch = fresh ? p.getFloat("Pitch") : Mth.lerp(t, giant.pPitch, giant.cPitch);
            giant.pHead = fresh ? p.getFloat("Head") : Mth.rotLerp(t, giant.pHead, giant.cHead);
            giant.pBody = fresh ? p.getFloat("Body") : Mth.rotLerp(t, giant.pBody, giant.cBody);
            giant.cx = p.getDouble("X");
            giant.cy = p.getDouble("Y");
            giant.cz = p.getDouble("Z");
            giant.cYaw = p.getFloat("Yaw");
            giant.cPitch = p.getFloat("Pitch");
            giant.cHead = p.getFloat("Head");
            giant.cBody = p.getFloat("Body");
            giant.updatedAt = now;
            giant.player.setPose(p.getBoolean("Crouch") ? Pose.CROUCHING : Pose.STANDING);
            seen.add(id);
        }
        giants.keySet().retainAll(seen);
        if (tag.contains("Mobs"))
            mobs.update(tag.getList("Mobs", Tag.TAG_COMPOUND), level);
    }

    private static float progress(Giant giant, long now, float partialTick)
    {
        return Mth.clamp((now - giant.updatedAt + partialTick) / 4f, 0, 1);
    }

    // ---- Drawing ----

    /** The kind of globe you're inside, or null when not inside one. */
    @Nullable
    static com.nick.snowglobe.GlobeKind insideKind()
    {
        return active() ? com.nick.snowglobe.GlobeKind.values()[Math.floorMod(view.kind(), com.nick.snowglobe.GlobeKind.values().length)] : null;
    }

    /** Time of day inside the globe you're in (GlobeSavedData.PHASE_*), day when not inside one. */
    static int insidePhase()
    {
        return active() ? view.phase() : 0;
    }

    /** The season inside the globe you're in (GlobeSavedData.WINTER and so on). */
    static int insideSeason()
    {
        return active() ? view.season() : 0;
    }

    /** The middle of the build space of the globe you're in, or null when not inside one. */
    @Nullable
    static Vec3 insideCenter()
    {
        if (!active() || view.spaceSize() <= 0)
            return null;
        double half = view.spaceSize() / 2.0;
        return Vec3.atLowerCornerOf(view.interiorMin()).add(half, half, half);
    }

    /** The radius of the round build space you're in (0 when not inside one). */
    static double insideRadius()
    {
        return active() ? view.spaceSize() / 2.0 : 0;
    }

    /** Where the globe you're inside stands outside (its storm's seed), or null when not inside one. */
    @Nullable
    static BlockPos insideGlobePos()
    {
        return active() ? view.globePos() : null;
    }

    /** True while inside a globe with a view to draw. */
    static boolean active()
    {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != GlobeSpace.LEVEL)
        {
            view = null;
            giants.clear();
            mobs = new MiniEntities();
            dropWorldMesh();
        }
        return view != null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        // Drawn after the world's solid blocks but before its see-through ones, so the globe's glass walls go over it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS || !active())
            return;
        View v = view;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Vec3 camera = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blocks = mc.getBlockRenderer();

        PoseStack poseStack = new PoseStack();
        // At this stage the camera's turn isn't in the render system's model-view yet (the world's solid blocks
        // get it passed in directly); add it ourselves, unless it's already there.
        if (RenderSystem.getModelViewMatrix().equals(new Matrix4f(), 1e-5f))
            poseStack.last().pose().mul(event.getModelViewMatrix());
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        // The miniature's placement, run backwards: outside world -> globe block -> miniature cells -> build space.
        poseStack.translate(v.interiorMin().getX() + v.scale() * v.pivot().x, v.interiorMin().getY() + v.scale() * v.pivot().y,
                v.interiorMin().getZ() + v.scale() * v.pivot().z);
        float giant = v.scale() / v.cell();
        poseStack.scale(giant, giant, giant);
        poseStack.translate(-v.center().x, -v.center().y, -v.center().z);
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(90 * v.turns()));
        poseStack.scale(1f / v.display(), 1f / v.display(), 1f / v.display());
        poseStack.translate(-(v.globePos().getX() + 0.5), -v.globePos().getY(), -(v.globePos().getZ() + 0.5));

        if (worldMesh == null)
            worldMesh = BakedMesh.bake(recorder -> {
                PoseStack local = new PoseStack();
                for (BlockPos pos : v.visible())
                {
                    int x = pos.getX() - v.origin().getX(), y = pos.getY() - v.origin().getY(), z = pos.getZ() - v.origin().getZ();
                    int i = (y * v.sz() + z) * v.sx() + x;
                    BlockState state = v.states()[i];
                    if (state.getRenderShape() == RenderShape.INVISIBLE)
                        continue;
                    int packed = v.light()[i] & 0xFF;
                    local.pushPose();
                    local.translate(x, y, z);
                    blocks.renderSingleBlock(state, local, recorder, LightTexture.pack(packed & 15, packed >> 4),
                            OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
                    local.popPose();
                }
            });
        poseStack.pushPose();
        poseStack.translate(v.origin().getX(), v.origin().getY(), v.origin().getZ());
        worldMesh.draw(poseStack.last().pose());
        poseStack.popPose();

        // The other globes around, with their miniatures (their glass drawn here too, straight away).
        if (level != null)
        {
            float ticks = level.getGameTime() % 240000 + partialTick;
            for (Neighbour globe : v.globes())
            {
                int x = globe.pos().getX() - v.origin().getX(), y = globe.pos().getY() - v.origin().getY(), z = globe.pos().getZ() - v.origin().getZ();
                if (x < 0 || y < 0 || z < 0 || x >= v.sx() || y >= v.sy() || z >= v.sz())
                    continue;
                int i = (y * v.sz() + z) * v.sx() + x;
                BlockState state = v.states()[i];
                if (!(state.getBlock() instanceof GlobeBlock block))
                    continue;
                int packed = v.light()[i] & 0xFF;
                poseStack.pushPose();
                poseStack.translate(globe.pos().getX(), globe.pos().getY(), globe.pos().getZ());
                GlobeRenderer.renderPlaced(state, block.kind(), globe.snapshot(), poseStack, buffers, LightTexture.pack(packed & 15, packed >> 4),
                        ticks, blocks, null, null, level, globe.pos(), -1, state.getValue(GlobeBlock.SPINNING), globe.look());
                poseStack.popPose();
            }
        }

        // The animals and monsters around the globe, wandering past.
        if (level != null)
        {
            poseStack.pushPose();
            poseStack.translate(v.globePos().getX(), v.globePos().getY(), v.globePos().getZ());
            mobs.render(poseStack, buffers, LightTexture.FULL_BRIGHT, partialTick, level);
            poseStack.popPose();
        }

        // The players near the globe, peering in.
        if (level != null && !giants.isEmpty())
        {
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            dispatcher.setRenderShadow(false);
            long now = level.getGameTime();
            for (Giant g : giants.values())
            {
                float t = progress(g, now, partialTick);
                double x = Mth.lerp(t, g.px, g.cx), y = Mth.lerp(t, g.py, g.cy), z = Mth.lerp(t, g.pz, g.cz);
                RemotePlayer p = g.player;
                p.setPos(x, y, z);
                float yaw = Mth.rotLerp(t, g.pYaw, g.cYaw);
                p.setYRot(yaw);
                p.yRotO = yaw;
                p.setXRot(Mth.lerp(t, g.pPitch, g.cPitch));
                p.xRotO = p.getXRot();
                p.yHeadRot = p.yHeadRotO = Mth.rotLerp(t, g.pHead, g.cHead);
                p.yBodyRot = p.yBodyRotO = Mth.rotLerp(t, g.pBody, g.cBody);
                dispatcher.render(p, x, y, z, yaw, partialTick, poseStack, buffers, LightTexture.FULL_BRIGHT);
            }
            dispatcher.setRenderShadow(mc.options.entityShadows().get());
        }
        buffers.endBatch();
    }

    /**
     * Fog fitted to the giant world: clear nearby, thickening towards where the shown part of the world ends, so it
     * fades into a haze instead of stopping at a hard edge.
     */
    @SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog event)
    {
        if (!active())
            return;
        View v = view;
        // How far the shown world reaches from the globe, in blocks of the build space.
        double giant = v.scale() / (v.cell() * v.display());
        double reach = Math.min(v.sx(), v.sz()) / 2.0 * giant;
        event.setNearPlaneDistance((float) (reach * 0.3));
        event.setFarPlaneDistance((float) (reach * 0.92));
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }
}
