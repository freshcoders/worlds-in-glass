package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A display turntable: a globe placed on top of it will slowly spin round.
 */
public class TurntableBlock extends Block
{
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0, 0, 0, 16, 12, 16),
            Block.box(1, 12, 1, 15, 16, 15)
    );

    public TurntableBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }
}
