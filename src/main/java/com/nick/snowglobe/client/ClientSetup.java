package com.nick.snowglobe.client;

import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT)
public final class ClientSetup
{
    private ClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(SnowGlobeMod.GLOBE_BLOCK_ENTITY.get(), GlobeRenderer::new);
    }

    /** Globe items are drawn with their miniature, so you can see what's inside before placing one. */
    @SubscribeEvent
    public static void registerItemRenderers(RegisterClientExtensionsEvent event)
    {
        event.registerItem(new IClientItemExtensions()
        {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                return GlobeItemRenderer.get();
            }
        }, SnowGlobeMod.GLOBE_ITEMS.values().stream().map(item -> item.get()).toArray(net.minecraft.world.item.Item[]::new));
    }
}
