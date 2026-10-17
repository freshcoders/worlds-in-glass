package com.nick.worldsinglass;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The glassblower's pipe: blows glass into the bulbs every globe is made from. It isn't used up in crafting; it comes
 * back a little worn each time, and wears out after a while.
 */
public class GlassblowerPipeItem extends Item
{
    public GlassblowerPipeItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public boolean hasCraftingRemainingItem(ItemStack stack)
    {
        return true;
    }

    @Override
    public ItemStack getCraftingRemainingItem(ItemStack stack)
    {
        ItemStack worn = stack.copyWithCount(1);
        worn.setDamageValue(worn.getDamageValue() + 1);
        return worn.getDamageValue() >= worn.getMaxDamage() ? ItemStack.EMPTY : worn;
    }
}
