package com.nick.snowglobe;

import com.nick.snowglobe.client.GlobeItemRenderer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/** A globe as an item: drawn with its miniature, so you can see what's inside before placing it. */
public class GlobeItem extends BlockItem
{
    public GlobeItem(GlobeBlock block, Properties properties)
    {
        super(block, properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer)
    {
        consumer.accept(new IClientItemExtensions()
        {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                return GlobeItemRenderer.get();
            }
        });
    }
}
