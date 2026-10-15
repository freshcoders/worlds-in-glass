package com.nick.snowglobe;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The glass shell around a globe's build space. With an empty hand, right-click it to leave the globe. */
public class GlobeWallBlock extends TransparentBlock
{
    private static final MapCodec<GlobeWallBlock> CODEC = simpleCodec(GlobeWallBlock::new);

    public GlobeWallBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    protected MapCodec<? extends TransparentBlock> codec()
    {
        return CODEC;
    }

    /** Holding something: let it be placed against the wall as usual. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit)
    {
        return stack.isEmpty() ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
    {
        if (player instanceof ServerPlayer serverPlayer)
            GlobeSpace.leave(serverPlayer);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
