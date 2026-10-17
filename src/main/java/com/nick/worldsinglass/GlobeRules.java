package com.nick.worldsinglass;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * What can't happen inside a globe. Deaths are safe in there, so a boss fight would cost nothing (and a Wither would
 * blow someone's little world apart): the Wither can't be contained in a globe. Building one gives the skulls and
 * soul sand back.
 */
public final class GlobeRules
{
    private GlobeRules() {}

    static void onEntityJoin(EntityJoinLevelEvent event)
    {
        if (!(event.getEntity() instanceof WitherBoss wither) || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != GlobeSpace.LEVEL)
            return;
        event.setCanceled(true);
        if (event.loadedFromDisk())
            return;
        // Freshly built: hand the parts back where it would have stood.
        for (ItemStack stack : new ItemStack[] {new ItemStack(Items.WITHER_SKELETON_SKULL, 3), new ItemStack(Items.SOUL_SAND, 4)})
            level.addFreshEntity(new ItemEntity(level, wither.getX(), wither.getY() + 0.5, wither.getZ(), stack));
        for (ServerPlayer player : level.players())
            if (player.distanceToSqr(wither) < 32 * 32)
                player.displayClientMessage(Component.translatable("worldsinglass.message.no_wither"), true);
    }
}
