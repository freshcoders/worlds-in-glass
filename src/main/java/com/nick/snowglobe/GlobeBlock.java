package com.nick.snowglobe;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** A snow globe, terrarium, storm globe or ship in a bottle. Right-click to step inside and build. */
public class GlobeBlock extends BaseEntityBlock
{
    private final GlobeKind kind;
    private final MapCodec<GlobeBlock> codec;

    public GlobeBlock(GlobeKind kind, Properties properties)
    {
        super(properties);
        this.kind = kind;
        this.codec = simpleCodec(p -> new GlobeBlock(kind, p));
    }

    public GlobeKind kind()
    {
        return kind;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec()
    {
        return codec;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return kind.shape;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new GlobeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type)
    {
        return level.isClientSide ? null : createTickerHelper(type, SnowGlobeMod.GLOBE_BLOCK_ENTITY.get(), GlobeBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack)
    {
        super.setPlacedBy(level, pos, state, placer, stack);
        // A fresh globe gets its build space right away; a globe picked up and placed again keeps its old one.
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof GlobeBlockEntity globe)
        {
            globe.ensureGlobe(serverLevel.getServer());
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Holding a Create schematic: import it into the globe instead of stepping inside. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit)
    {
        if (!GlobeImport.isCreateSchematic(stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof GlobeBlockEntity globe)
            GlobeImport.importHeldSchematic(serverPlayer, stack, globe);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
    {
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof GlobeBlockEntity globe)
            GlobeSpace.enter(serverPlayer, globe);
        return InteractionResult.CONSUME;
    }
}
