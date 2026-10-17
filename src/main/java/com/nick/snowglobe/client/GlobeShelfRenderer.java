package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.snowglobe.GlobeBlock;
import com.nick.snowglobe.GlobeItem;
import com.nick.snowglobe.GlobeShelfBlock;
import com.nick.snowglobe.GlobeShelfBlockEntity;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Draws the globes standing on a shelf, half size, with their miniatures and glass, turned to face the room. */
public class GlobeShelfRenderer implements BlockEntityRenderer<GlobeShelfBlockEntity>
{
    private static final float SCALE = 0.5f;

    public GlobeShelfRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(GlobeShelfBlockEntity shelf, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay)
    {
        if (!(shelf.getLevel() instanceof ClientLevel level))
            return;
        Direction front = shelf.getBlockState().getValue(GlobeShelfBlock.FACING);
        Direction along = front.getCounterClockWise();
        // Side-on to someone standing in front, as when placed by hand.
        Direction facing = front.getOpposite().getClockWise();
        float ticks = level.getGameTime() % 240000 + partialTick;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        for (int slot = 0; slot < 2; slot++)
        {
            ItemStack stack = shelf.get(slot);
            if (!(stack.getItem() instanceof GlobeItem item) || !(item.getBlock() instanceof GlobeBlock block))
                continue;
            float offset = slot == 0 ? -0.22f : 0.22f;
            double cx = 0.5 + along.getStepX() * offset, cz = 0.5 + along.getStepZ() * offset;
            BlockState state = block.defaultBlockState().setValue(GlobeBlock.FACING, facing);
            CustomData data = stack.get(SnowGlobeMod.GLOBE_DATA.get());
            GlobeRenderer.Look look = data != null ? GlobeRenderer.Look.of(data.getUnsafe()) : GlobeRenderer.Look.DEFAULT;

            // Where you're looking from, in the globe's own (turned) frame, for the glint on its glass.
            float turn = (float) Math.toRadians(-90 * GlobeBlock.quarterTurns(facing));
            Vec3 center = Vec3.atLowerCornerOf(shelf.getBlockPos()).add(cx, 2 / 16.0 + 6 / 16.0 * SCALE, cz);
            Vec3 eye = camera.subtract(center).normalize().yRot(-turn);

            poseStack.pushPose();
            poseStack.translate(cx, 2 / 16.0, cz);
            poseStack.scale(SCALE, SCALE, SCALE);
            poseStack.translate(-0.5, 0, -0.5);
            GlobeRenderer.renderPlaced(state, block.kind(), GlobeItemRenderer.get().snapshotOf(stack), poseStack, buffers, light, ticks,
                    Minecraft.getInstance().getBlockRenderer(), eye, null, level, shelf.getBlockPos(), -1, true, look);
            poseStack.popPose();
        }
    }
}
