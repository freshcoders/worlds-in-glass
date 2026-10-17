package com.nick.worldsinglass.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.worldsinglass.GlobeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * A snapshot seen as a tiny world, so Minecraft's own water renderer can draw the miniature's water:
 * it asks about neighbours (to hide inner faces and slope the surface) and light, which this answers.
 */
final class SnapshotView implements BlockAndTintGetter
{
    private final GlobeSnapshot snapshot;
    private final ClientLevel level;
    /** Where the globe block is in the real world: its biome colours the water. */
    private final BlockPos tintPos;
    private final int sky, block;

    SnapshotView(GlobeSnapshot snapshot, ClientLevel level, BlockPos tintPos, int light)
    {
        this.snapshot = snapshot;
        this.level = level;
        this.tintPos = tintPos;
        this.sky = LightTexture.sky(light);
        this.block = LightTexture.block(light);
    }

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        int s = snapshot.size();
        if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= s || pos.getY() >= s || pos.getZ() >= s)
            return Blocks.AIR.defaultBlockState();
        return snapshot.get(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos)
    {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos)
    {
        return null;
    }

    @Override
    public int getHeight()
    {
        return snapshot.size();
    }

    @Override
    public int getMinBuildHeight()
    {
        return 0;
    }

    @Override
    public float getShade(Direction direction, boolean shade)
    {
        return level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine()
    {
        return level.getLightEngine();
    }

    /** The whole miniature shares the light where the globe stands. */
    @Override
    public int getBrightness(LightLayer layer, BlockPos pos)
    {
        return layer == LightLayer.SKY ? sky : block;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening)
    {
        return Math.max(block, sky - darkening);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver)
    {
        return level.getBlockTint(tintPos, resolver);
    }

    /**
     * Takes the water renderer's vertices (in miniature cell units) and passes them on through the current pose,
     * with the overlay the entity format needs, so they go in the same buffers as the rest of the globe.
     * The water renderer sets the normal last, so that's when a vertex is complete.
     */
    static final class PosedConsumer implements VertexConsumer
    {
        private final VertexConsumer out;
        private final PoseStack.Pose pose;
        private float x, y, z, u, v;
        private int r = 255, g = 255, b = 255, a = 255, light;
        /** Minecraft's water renderer writes positions within a 16-block section; this puts them back in place. */
        private int ox, oy, oz;

        PosedConsumer(VertexConsumer out, PoseStack.Pose pose)
        {
            this.out = out;
            this.pose = pose;
        }

        /** Call before rendering each fluid block: where its 16-block section starts. */
        PosedConsumer at(BlockPos pos)
        {
            ox = pos.getX() & ~15;
            oy = pos.getY() & ~15;
            oz = pos.getZ() & ~15;
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a)
        {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v)
        {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v)
        {
            this.light = (u & 0xFFFF) | (v & 0xFFFF) << 16;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz)
        {
            out.addVertex(pose, x + ox, y + oy, z + oz).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light).setNormal(pose, nx, ny, nz);
            return this;
        }
    }
}
