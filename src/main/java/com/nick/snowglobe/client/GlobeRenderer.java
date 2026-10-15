package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.snowglobe.GlobeBlockEntity;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.GlobeSpace;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.model.data.ModelData;

/** Draws the globe's contents as a miniature inside its glass case. */
public class GlobeRenderer implements BlockEntityRenderer<GlobeBlockEntity>
{
    /** How wide the miniature is, as a fraction of a block (the glass case is 12/16 wide). */
    private static final float WIDTH = 11f / 16f;
    /** Height of the wooden base the miniature stands on. */
    private static final float BASE_TOP = 3f / 16f;

    private final BlockRenderDispatcher blocks;

    public GlobeRenderer(BlockEntityRendererProvider.Context context)
    {
        this.blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(GlobeBlockEntity globe, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay)
    {
        GlobeSnapshot snapshot = globe.getSnapshot();
        if (snapshot == null)
            return;

        float scale = WIDTH / GlobeSpace.SIZE;
        poseStack.pushPose();
        poseStack.translate(0.5f - WIDTH / 2, BASE_TOP + 0.002f, 0.5f - WIDTH / 2);
        poseStack.scale(scale, scale, scale);
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
}
