package com.nick.snowglobe;

import net.minecraft.world.item.BlockItem;

/** A globe as an item. Its look (with the miniature) comes from GlobeItemRenderer, hooked up in ClientSetup. */
public class GlobeItem extends BlockItem
{
    public GlobeItem(GlobeBlock block, Properties properties)
    {
        super(block, properties);
    }
}
