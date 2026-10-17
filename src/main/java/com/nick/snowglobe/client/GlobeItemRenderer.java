package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.snowglobe.GlobeBlock;
import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.GlobeSnapshot;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/** Draws globe items: the block's model plus the miniature saved in the item. */
public class GlobeItemRenderer extends BlockEntityWithoutLevelRenderer
{
    private static GlobeItemRenderer instance;

    /** Parsed snapshots per item data, so a held globe isn't decoded again every frame. */
    private final Map<CustomData, GlobeSnapshot> snapshots = new IdentityHashMap<>();

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
        float ticks = (System.currentTimeMillis() % 12_000_000L) / 50f;
        GlobeSnapshot snapshot = snapshotOf(stack);
        CustomData data = stack.get(SnowGlobeMod.GLOBE_DATA.get());
        GlobeRenderer.Look look = data != null ? GlobeRenderer.Look.of(data.getUnsafe()) : GlobeRenderer.Look.DEFAULT;
        // Water in the miniature needs a world for its colour; the player's surroundings will do.
        ClientLevel level = Minecraft.getInstance().level;
        BlockPos where = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.blockPosition() : BlockPos.ZERO;

        // The terrarium's glass is part of its model, so draw it after the miniature or it would hide it.
        if (block.kind() == GlobeKind.TERRARIUM || block.kind() == GlobeKind.AQUARIUM)
        {
            GlobeRenderer.renderGlobe(block.kind(), snapshot, poseStack, buffers, light, ticks, blocks, null, null, 0, level, where, 0, look);
            blocks.renderSingleBlock(state, poseStack, buffers, light, overlay, ModelData.EMPTY, null);
        }
        else
        {
            blocks.renderSingleBlock(state, poseStack, buffers, light, overlay, ModelData.EMPTY, null);
            GlobeRenderer.renderGlobe(block.kind(), snapshot, poseStack, buffers, light, ticks, blocks, null, null, 0, level, where, 0, look);
        }
    }

    /** The miniature saved in a globe item, parsed once per item data. */
    @Nullable
    GlobeSnapshot snapshotOf(ItemStack stack)
    {
        CustomData data = stack.get(SnowGlobeMod.GLOBE_DATA.get());
        if (data == null)
            return null;
        if (snapshots.size() > 64)
        {
            snapshots.values().forEach(s -> {
                if (s != null)
                    MiniatureCache.release(s);
            });
            snapshots.clear();
        }
        return snapshots.computeIfAbsent(data, d -> {
            var tag = d.copyTag();
            return tag.contains("Snapshot") ? GlobeSnapshot.fromTag(tag.getCompound("Snapshot")) : null;
        });
    }
}
