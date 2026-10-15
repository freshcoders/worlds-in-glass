package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The glass shell around a globe's build space. With an empty hand, right-click it to leave the globe. */
public class GlobeWallBlock extends AbstractGlassBlock
{
    public GlobeWallBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        // Holding something: let it be placed against the wall as usual.
        if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty())
            return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer)
            GlobeSpace.leave(serverPlayer);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
