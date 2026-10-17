package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.nick.snowglobe.GlobeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The block entities of one snapshot, rebuilt on this client from their saved data. Many modded blocks
 * (Create's copycats, for one) only look right with their block entity: their model reads its data, or a
 * block entity renderer draws part of them. Kept with the snapshot, so they're only rebuilt when it changes.
 */
final class MiniBlockEntities
{
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Renderers that failed once in a miniature (some expect a real world around them); skipped from then on. */
    private static final Set<BlockEntityType<?>> BROKEN = new HashSet<>();

    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();

    MiniBlockEntities(GlobeSnapshot snapshot, @Nullable ClientLevel level)
    {
        ClientLevel world = level != null ? level : Minecraft.getInstance().level;
        if (world == null)
            return;
        HolderLookup.Provider registries = world.registryAccess();
        for (BlockPos pos : snapshot.visibleBlocks())
        {
            CompoundTag tag = snapshot.blockEntity(pos.getX(), pos.getY(), pos.getZ());
            if (tag == null)
                continue;
            try
            {
                BlockEntity be = BlockEntity.loadStatic(pos, snapshot.get(pos.getX(), pos.getY(), pos.getZ()), tag, registries);
                if (be != null)
                {
                    be.setLevel(world);
                    entities.put(pos, be);
                }
            }
            catch (RuntimeException e)
            {
                LOGGER.debug("Couldn't rebuild a {} for a globe miniature", tag.getString("id"), e);
            }
        }
    }

    /** One cell's model (with its block entity's data, if any), for baking into the miniature's mesh. */
    void renderModel(BlockPos pos, BlockState state, PoseStack poseStack, MultiBufferSource buffers, int light, BlockRenderDispatcher blocks)
    {
        BlockEntity be = entities.get(pos);
        RenderShape shape = state.getRenderShape();
        if (shape == RenderShape.MODEL)
            blocks.renderSingleBlock(state, poseStack, buffers, light, OverlayTexture.NO_OVERLAY, be != null ? be.getModelData() : ModelData.EMPTY, null);
        else if (shape == RenderShape.ENTITYBLOCK_ANIMATED)
            // Drawn like its item (chests, beds, ...), which also covers what its renderer would draw.
            blocks.renderSingleBlock(state, poseStack, buffers, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
    }

    private static void renderNestedGlobe(com.nick.snowglobe.GlobeBlockEntity globe, BlockPos pos, PoseStack poseStack, MultiBufferSource buffers,
                                          int light, float partialTick)
    {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return;
        float ticks = level.getGameTime() % 240000 + partialTick;
        poseStack.pushPose();
        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
        GlobeRenderer.renderPlaced(globe.getBlockState(), globe.kind(), globe.getSnapshot(), poseStack, buffers, light, ticks,
                Minecraft.getInstance().getBlockRenderer(), null, null, level, pos, -1, false,
                new GlobeRenderer.Look(globe.phase(), globe.season()));
        poseStack.popPose();
    }

    /** The parts drawn fresh every frame: block entity renderers (moving parts, text, ...). Pose in cell units. */
    void renderDynamic(PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick)
    {
        for (Map.Entry<BlockPos, BlockEntity> entry : entities.entrySet())
        {
            BlockEntity be = entry.getValue();
            if (be instanceof com.nick.snowglobe.GlobeBlockEntity globe)
            {
                // A globe standing in this globe: drawn with its own miniature, its glass straight away.
                renderNestedGlobe(globe, entry.getKey(), poseStack, buffers, light, partialTick);
                continue;
            }
            if (be.getBlockState().getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED || BROKEN.contains(be.getType()))
                continue;
            BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer == null)
                continue;
            BlockPos pos = entry.getKey();
            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            try
            {
                renderer.render(be, partialTick, poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
            }
            catch (Throwable e)
            {
                BROKEN.add(be.getType());
                LOGGER.debug("Block entity renderer for {} doesn't work in a globe miniature; skipping it", be.getType(), e);
            }
            finally
            {
                poseStack.popPose();
            }
        }
    }
}
