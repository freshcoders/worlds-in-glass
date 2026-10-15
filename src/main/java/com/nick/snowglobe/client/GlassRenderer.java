package com.nick.snowglobe.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Globe glass is drawn last, after everything else in the world (water included), and without hiding
 * what's behind it. Drawn together with the rest, the rough sorting of see-through things could put a piece of
 * glass before the water behind it, which then hid the water.
 */
@EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT)
public final class GlassRenderer
{
    /** Glass from one globe: where it is (a copy of the pose) and how to draw it. */
    private record Pending(PoseStack.Pose pose, BiConsumer<VertexConsumer, PoseStack.Pose> draw) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    private GlassRenderer() {}

    /** Draws the glass now (items, which aren't part of the world) or queues it until the world is drawn. */
    static void draw(PoseStack.Pose pose, MultiBufferSource buffers, boolean inWorld, BiConsumer<VertexConsumer, PoseStack.Pose> draw)
    {
        if (inWorld)
            PENDING.add(new Pending(pose.copy(), draw));
        else
            draw.accept(buffers.getBuffer(Types.GLASS), pose);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || PENDING.isEmpty())
            return;
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(Types.GLASS);
        for (Pending pending : PENDING)
            pending.draw().accept(out, pending.pose());
        buffers.endBatch(Types.GLASS);
        PENDING.clear();
    }

    /** Subclass only to reach RenderType's building blocks. */
    private abstract static class Types extends RenderType
    {
        /** Like the translucent block sheet, but doesn't write depth: glass never hides what's behind it. */
        static final RenderType GLASS = create(SnowGlobeMod.MODID + ":globe_glass", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 4096, false, true,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                        .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(true));

        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean crumbling,
                      boolean sort, Runnable setup, Runnable clear)
        {
            super(name, format, mode, bufferSize, crumbling, sort, setup, clear);
        }
    }
}
