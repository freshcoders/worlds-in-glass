package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.snowglobe.GlobeBlock;
import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/** Draws globe items: the block's model plus the miniature saved in the item. */
public class GlobeItemRenderer extends BlockEntityWithoutLevelRenderer
{
    private static GlobeItemRenderer instance;

    /** Parsed snapshots per item tag, so a held globe isn't decoded again every frame. */
    private final Map<CompoundTag, GlobeSnapshot> snapshots = new WeakHashMap<>();

    private GlobeItemRenderer()
    {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static GlobeItemRenderer get()
    {
        if (instance == null)
            instance = new GlobeItemRenderer();
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay)
    {
        if (!(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof GlobeBlock block))
            return;
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        BlockState state = block.defaultBlockState();
        float seconds = (System.currentTimeMillis() % 1_200_000L) / 1000f;
        GlobeSnapshot snapshot = snapshotOf(stack);

        // The terrarium's glass is part of its model, so draw it after the miniature or it would hide it.
        if (block.kind() == GlobeKind.TERRARIUM)
        {
            GlobeRenderer.renderGlobe(block.kind(), snapshot, poseStack, buffers, light, seconds, blocks);
            blocks.renderSingleBlock(state, poseStack, buffers, light, overlay, ModelData.EMPTY, null);
        }
        else
        {
            blocks.renderSingleBlock(state, poseStack, buffers, light, overlay, ModelData.EMPTY, null);
            GlobeRenderer.renderGlobe(block.kind(), snapshot, poseStack, buffers, light, seconds, blocks);
        }
    }

    @Nullable
    private GlobeSnapshot snapshotOf(ItemStack stack)
    {
        CompoundTag tag = stack.getTagElement("BlockEntityTag");
        if (tag == null || !tag.contains("Snapshot"))
            return null;
        CompoundTag snapshotTag = tag.getCompound("Snapshot");
        return snapshots.computeIfAbsent(snapshotTag, GlobeSnapshot::fromTag);
    }
}
