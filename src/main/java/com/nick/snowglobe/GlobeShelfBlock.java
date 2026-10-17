package com.nick.snowglobe;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A collector's shelf: holds two globes side by side, showing their miniatures (kept up to date with what's built
 * inside). Right-click with a globe to put it on the left or right; with an empty hand to take one off.
 */
public class GlobeShelfBlock extends BaseEntityBlock
{
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final MapCodec<GlobeShelfBlock> CODEC = simpleCodec(GlobeShelfBlock::new);
    private static final VoxelShape ALONG_X = Shapes.or(Block.box(0, 0, 0, 16, 2, 16), Block.box(0, 2, 1, 1.5, 9, 15), Block.box(14.5, 2, 1, 16, 9, 15));
    private static final VoxelShape ALONG_Z = Shapes.or(Block.box(0, 0, 0, 16, 2, 16), Block.box(1, 2, 0, 15, 9, 1.5), Block.box(1, 2, 14.5, 15, 9, 16));

    public GlobeShelfBlock(Properties properties)
    {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec()
    {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(FACING);
    }

    /** Faces whoever placed it. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation)
    {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror)
    {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? ALONG_X : ALONG_Z;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new GlobeShelfBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type)
    {
        return level.isClientSide ? null : createTickerHelper(type, SnowGlobeMod.SHELF_BLOCK_ENTITY.get(), GlobeShelfBlockEntity::serverTick);
    }

    /** Which half of the shelf was clicked, 0 (left, seen from the front) or 1 (right). */
    static int slotAt(BlockState state, BlockPos pos, Vec3 hit)
    {
        Direction along = state.getValue(FACING).getCounterClockWise();
        double u = (hit.x - pos.getX() - 0.5) * along.getStepX() + (hit.z - pos.getZ() - 0.5) * along.getStepZ();
        return u < 0 ? 0 : 1;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit)
    {
        if (!(stack.getItem() instanceof GlobeItem) || !(level.getBlockEntity(pos) instanceof GlobeShelfBlockEntity shelf))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        int slot = slotAt(state, pos, hit.getLocation());
        if (!shelf.get(slot).isEmpty())
            slot = 1 - slot;
        if (!shelf.get(slot).isEmpty())
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide)
        {
            shelf.set(slot, stack.copyWithCount(1));
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.GLASS_PLACE, SoundSource.BLOCKS, 0.8f, 1.2f);
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                GlobeAdvancements.award(serverPlayer, "on_display");
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
    {
        if (!(level.getBlockEntity(pos) instanceof GlobeShelfBlockEntity shelf))
            return InteractionResult.PASS;
        int slot = slotAt(state, pos, hit.getLocation());
        if (shelf.get(slot).isEmpty())
            slot = 1 - slot;
        if (shelf.get(slot).isEmpty())
            return InteractionResult.PASS;
        if (!level.isClientSide)
        {
            ItemStack taken = shelf.get(slot);
            shelf.set(slot, ItemStack.EMPTY);
            if (!player.addItem(taken))
                player.drop(taken, false);
            level.playSound(null, pos, SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 0.8f, 1.2f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston)
    {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof GlobeShelfBlockEntity shelf)
            Containers.dropContents(level, pos, shelf.items());
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
