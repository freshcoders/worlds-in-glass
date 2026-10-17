package com.nick.snowglobe;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * A snow globe, terrarium, storm globe or ship in a bottle. Right-click to shake it; right-click its base to turn
 * it; sneak and right-click (with an empty hand) to step inside and build.
 */
public class GlobeBlock extends BaseEntityBlock
{
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DisplaySize> DISPLAY = EnumProperty.create("display", DisplaySize.class);
    /** Standing on a turntable: the whole globe, base and all, is drawn spinning by the renderer instead of the model. */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty SPINNING =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("spinning");
    /** Block event id for a shake, sent to everyone watching so they see it too. */
    public static final int EVENT_SHAKE = 1;
    /** Block event id for a knock on the glass, so everyone near hears it (as their settings allow). */
    public static final int EVENT_KNOCK = 2;

    private final GlobeKind kind;
    private final MapCodec<GlobeBlock> codec;
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    public GlobeBlock(GlobeKind kind, Properties properties)
    {
        super(properties);
        this.kind = kind;
        this.codec = simpleCodec(p -> new GlobeBlock(kind, p));
        for (Direction facing : Direction.Plane.HORIZONTAL)
            shapes.put(facing, rotate(kind.shape, facing));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST).setValue(DISPLAY, DisplaySize.SMALL).setValue(SPINNING, false));
    }

    public GlobeKind kind()
    {
        return kind;
    }

    /** Quarter turns clockwise (seen from above) from facing east, the way the models are made. */
    public static int quarterTurns(Direction facing)
    {
        return switch (facing)
        {
            case SOUTH -> 1;
            case WEST -> 2;
            case NORTH -> 3;
            default -> 0;
        };
    }

    /** Turns a shape made facing east to face the given way. */
    private static VoxelShape rotate(VoxelShape shape, Direction facing)
    {
        VoxelShape result = Shapes.empty();
        int turns = quarterTurns(facing);
        for (AABB box : shape.toAabbs())
        {
            double x0 = box.minX - 0.5, x1 = box.maxX - 0.5, z0 = box.minZ - 0.5, z1 = box.maxZ - 0.5;
            for (int i = 0; i < turns; i++)
            {
                // A quarter turn clockwise: east goes to south, (x, z) -> (-z, x).
                double nx0 = -z1, nx1 = -z0, nz0 = x0, nz1 = x1;
                x0 = nx0;
                x1 = nx1;
                z0 = nz0;
                z1 = nz1;
            }
            result = Shapes.or(result, Shapes.box(x0 + 0.5, box.minY, z0 + 0.5, x1 + 0.5, box.maxY, z1 + 0.5));
        }
        return result.optimize();
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec()
    {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(FACING, DISPLAY, SPINNING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        // Side-on to you: a bottle lies across your view, its cork to your right.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getClockWise())
                .setValue(SPINNING, context.getLevel().getBlockState(context.getClickedPos().below()).is(SnowGlobeMod.TURNTABLE.get()));
    }

    /** Starts or stops spinning when a turntable is put under it or taken away. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, net.minecraft.world.level.LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos)
    {
        if (direction == Direction.DOWN)
            return state.setValue(SPINNING, neighbour.is(SnowGlobeMod.TURNTABLE.get()));
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
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
        // Medium and large globes are drawn bigger than their block; the block itself stays one block.
        return state.getValue(DISPLAY) == DisplaySize.SMALL ? shapes.get(state.getValue(FACING)) : Shapes.block();
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
            // Placed in creative, a new globe comes with a starter scene; in survival it starts empty, unless it's one
            // bought or found with a little world already waiting in it.
            net.minecraft.world.item.component.CustomData data = stack.get(SnowGlobeMod.GLOBE_DATA.get());
            boolean scene = data != null && data.copyTag().getBoolean("Scene");
            globe.ensureGlobe(serverLevel.getServer(), scene || placer instanceof Player player && player.getAbilities().instabuild);
            globe.updateDisplaySize();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Holding something that goes into a globe: a Create schematic imports it; a bucket of fish lets them swim in;
     * a water bucket catches one back out.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit)
    {
        // A name tag with a name on it: the globe takes the name, on a little plate on its base.
        if (stack.is(net.minecraft.world.item.Items.NAME_TAG) && stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME))
        {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof GlobeBlockEntity globe)
            {
                globe.setName(stack.getHoverName());
                stack.consume(1, player);
                level.playSound(null, pos, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.6f, 1.6f);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.getItem() instanceof net.minecraft.world.item.MobBucketItem || stack.is(net.minecraft.world.item.Items.WATER_BUCKET))
        {
            if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof GlobeBlockEntity globe)
            {
                if (stack.is(net.minecraft.world.item.Items.WATER_BUCKET))
                {
                    if (!GlobeCreatures.catchFish(serverPlayer, hand, globe))
                        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                }
                else
                    GlobeCreatures.releaseFish(serverPlayer, hand, stack, globe);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!GlobeImport.isCreateSchematic(stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof GlobeBlockEntity globe)
            GlobeImport.importHeldSchematic(serverPlayer, stack, globe);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Height of the base or stand, as a fraction of the drawn globe: clicking below this turns the globe. */
    private double baseHeight()
    {
        return switch (kind)
        {
            case TERRARIUM, AQUARIUM -> 1.5 / 16;
            case NETHER_JAR -> 1.0 / 16;
            case SHIP_BOTTLE -> 2.5 / 16;
            default -> 2.75 / 16;
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
    {
        // Sneak + right-click: go inside, with a short zoom into the glass first.
        if (player.isSecondaryUseActive())
        {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof GlobeBlockEntity)
                GlobeView.startEntering(serverPlayer, serverLevel, pos, kind);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // Right-click the base: twist it a quarter turn, to see the other side.
        double hitHeight = hit.getLocation().y - pos.getY();
        if (hitHeight < baseHeight() * state.getValue(DISPLAY).scale)
        {
            if (!level.isClientSide)
            {
                level.setBlock(pos, state.setValue(FACING, state.getValue(FACING).getClockWise()), Block.UPDATE_ALL);
                level.playSound(null, pos, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.6f, 1.4f);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // Right-click the glass: shake it. Everyone inside feels it too.
        if (!level.isClientSide)
        {
            level.blockEvent(pos, this, EVENT_SHAKE, 0);
            if (level.getBlockEntity(pos) instanceof GlobeBlockEntity globe && globe.globeId() >= 0 && player instanceof ServerPlayer serverPlayer)
                shaken(serverPlayer, globe);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * A shake: felt by everyone inside, and in a snow globe it turns the season. Shaken into winter, snow settles on
     * everything; shaken into spring, it melts away.
     */
    private void shaken(ServerPlayer player, GlobeBlockEntity globe)
    {
        MinecraftServer server = player.server;
        int id = globe.globeId();
        GlobeView.notifyOccupants(server, id, com.nick.snowglobe.net.GlobeNetwork.GlobeEvent.SHAKEN, kind);
        GlobeAdvancements.award(player, "shake");
        if (!GlobeView.occupants(server, id).isEmpty() && kind == GlobeKind.STORM_GLOBE)
            for (ServerPlayer inside : GlobeView.occupants(server, id))
                GlobeAdvancements.award(inside, "eye_of_the_storm");
        if (kind != GlobeKind.SNOW_GLOBE)
            return;
        GlobeSavedData data = GlobeSavedData.get(server);
        int season = (data.seasonOf(id) + 1) % 4;
        data.setSeason(id, season);
        if (season == GlobeSavedData.WINTER)
        {
            GlobeSpace.settleSnow(server, id);
            GlobeAdvancements.award(player, "four_seasons");
        }
        else if (season == GlobeSavedData.SPRING)
            GlobeSpace.thaw(server, id);
        globe.refreshExtras();
        GlobeView.refreshOccupants(server, id);
    }

    /**
     * A storm globe's thunder, from outside: it must sound tiny, or people nearby would think a real storm is coming.
     * A little electric crackle, with the faintest high thunder under it, as the globe's own sound (not weather).
     */
    public static void tinyThunder(Level level, BlockPos pos)
    {
        if (!SnowGlobeConfig.thunderOutside())
            return;
        level.playLocalSound(pos, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.BLOCKS, 0.35f, 2.0f, false);
        level.playLocalSound(pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 0.08f, 2.0f, false);
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param)
    {
        if (id == EVENT_KNOCK)
        {
            if (level.isClientSide)
            {
                level.playLocalSound(pos, SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 1.0f, 1.3f, false);
                if (kind == GlobeKind.STORM_GLOBE)
                    tinyThunder(level, pos);
            }
            return true;
        }
        if (id != EVENT_SHAKE)
            return super.triggerEvent(state, level, pos, id, param);
        if (level.isClientSide && level.getBlockEntity(pos) instanceof GlobeBlockEntity globe)
        {
            globe.shakeStart = level.getGameTime();
            // Each globe sounds like what's in it.
            switch (kind)
            {
                case SNOW_GLOBE -> level.playLocalSound(pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f, 1.3f, false);
                case STORM_GLOBE ->
                {
                    level.playLocalSound(pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7f, 0.8f, false);
                    GlobeBlock.tinyThunder(level, pos);
                }
                case TERRARIUM ->
                {
                    level.playLocalSound(pos, SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 0.8f, 1.1f, false);
                    level.playLocalSound(pos, SoundEvents.AZALEA_LEAVES_STEP, SoundSource.BLOCKS, 1.0f, 1.2f, false);
                }
                case SHIP_BOTTLE ->
                {
                    level.playLocalSound(pos, SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 0.8f, 1.5f, false);
                    level.playLocalSound(pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.4f, 1.6f, false);
                }
                case DESERT_GLOBE ->
                {
                    level.playLocalSound(pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.6f, 1.1f, false);
                    level.playLocalSound(pos, SoundEvents.SAND_BREAK, SoundSource.BLOCKS, 0.6f, 1.5f, false);
                }
                case AQUARIUM ->
                {
                    level.playLocalSound(pos, SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 0.8f, 1.2f, false);
                    level.playLocalSound(pos, SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.BLOCKS, 0.7f, 1.4f, false);
                }
                case STAR_GLOBE ->
                {
                    level.playLocalSound(pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f, 1.8f, false);
                    level.playLocalSound(pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.5f, 1.6f, false);
                }
                case NETHER_JAR ->
                {
                    level.playLocalSound(pos, SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 0.8f, 1.0f, false);
                    level.playLocalSound(pos, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.8f, 1.3f, false);
                }
            }
        }
        return true;
    }
}
