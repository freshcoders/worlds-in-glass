package com.nick.worldsinglass;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The Globe Keeper's Handbook: lore and how-to, read like a written book. Given with the first globe you find. */
public class HandbookItem extends Item
{
    /** How many pages it has: worldsinglass.handbook.page.1 and on, in the language file. */
    public static final int PAGES = 16;

    public HandbookItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        if (level.isClientSide)
            GlobeClientHooks.openHandbook.run();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
