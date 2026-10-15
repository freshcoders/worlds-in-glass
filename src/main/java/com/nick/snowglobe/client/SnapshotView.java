package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.GlobeSpace;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;

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
        int s = GlobeSpace.SIZE;
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
        return GlobeSpace.SIZE;
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
     * Takes the water renderer's vertices (block format, in miniature cell units) and passes them on in
     * entity format through the current pose, so they can go in the same buffers as the rest of the globe.
     */
    static final class PosedConsumer implements VertexConsumer
    {
        private final VertexConsumer out;
        private final Matrix4f pose;
        private final Matrix3f normal;
        private float x, y, z, u, v, nx, ny = 1, nz;
        private int r = 255, g = 255, b = 255, a = 255, light;

        PosedConsumer(VertexConsumer out, PoseStack.Pose pose)
        {
            this.out = out;
            this.pose = pose.pose();
            this.normal = pose.normal();
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z)
        {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a)
        {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v)
        {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v)
        {
            this.light = (u & 0xFFFF) | (v & 0xFFFF) << 16;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z)
        {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }

        @Override
        public void endVertex()
        {
            out.vertex(pose, x, y, z).color(r, g, b, a).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(light).normal(normal, nx, ny, nz).endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a)
        {
        }

        @Override
        public void unsetDefaultColor()
        {
        }
    }
}
