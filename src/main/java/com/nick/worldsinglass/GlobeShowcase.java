package com.nick.worldsinglass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dev only: builds a small collector's gallery in front of you, for screenshots. A wood-panelled room seen through
 * a doorway: a row of collector's shelves on a low counter along the back wall, little pedestals under the side
 * windows, a big snow globe on a rug in the middle, and lanterns hanging from the beams.
 */
final class GlobeShowcase
{
    /** Half the room's inside width, its inside depth and its inside height. */
    private static final int HALF_WIDTH = 5, DEPTH = 9, HEIGHT = 5;

    private GlobeShowcase() {}

    /** The room's own directions: {@code u} to the right, {@code v} away from the door, {@code y} up from the floor. */
    private record Room(ServerLevel level, BlockPos origin, Direction look, Direction right)
    {
        BlockPos at(int u, int v, int y)
        {
            return origin.relative(right, u).relative(look, v).above(y);
        }

        void set(int u, int v, int y, BlockState state)
        {
            level.setBlock(at(u, v, y), state, Block.UPDATE_ALL);
        }
    }

    static int build(ServerPlayer player)
    {
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        if (level.dimension() == GlobeSpace.LEVEL)
        {
            player.sendSystemMessage(Component.literal("Build the showcase outside a globe."));
            return 0;
        }
        Direction look = player.getDirection();
        Direction right = look.getClockWise();
        // The front wall starts two blocks ahead; the floor is the block you stand on.
        Room room = new Room(level, player.blockPosition().relative(look, 2).below(), look, right);

        // Clear the space, with a stone foundation under the floor.
        for (int u = -HALF_WIDTH - 2; u <= HALF_WIDTH + 2; u++)
            for (int v = -1; v <= DEPTH + 2; v++)
            {
                for (int y = 1; y <= HEIGHT + 3; y++)
                    room.set(u, v, y, Blocks.AIR.defaultBlockState());
                for (int y = -3; y < 0; y++)
                    if (level.getBlockState(room.at(u, v, y)).canBeReplaced())
                        room.set(u, v, y, Blocks.STONE.defaultBlockState());
            }

        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState post = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        BlockState dark = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        int back = DEPTH + 1, side = HALF_WIDTH + 1;
        for (int u = -side; u <= side; u++)
            for (int v = 0; v <= back; v++)
            {
                boolean wall = Math.abs(u) == side || v == 0 || v == back;
                boolean corner = Math.abs(u) == side && (v == 0 || v == back || v % 4 == 0) || v == 0 && Math.abs(u) == 2;
                // Floor and ceiling.
                room.set(u, v, 0, wall ? planks : dark);
                room.set(u, v, HEIGHT + 1, v % 3 == 0 ? post.setValue(RotatedPillarBlock.AXIS, right.getAxis()) : dark);
                if (!wall)
                    continue;
                for (int y = 1; y <= HEIGHT; y++)
                {
                    BlockState block = corner ? post : v == back && y <= 4 && Math.abs(u) < side ? Blocks.BOOKSHELF.defaultBlockState() : planks;
                    room.set(u, v, y, block);
                }
            }

        // A doorway in the front wall, three wide, to look in through.
        for (int u = -1; u <= 1; u++)
            for (int y = 1; y <= 3; y++)
                room.set(u, 0, y, Blocks.AIR.defaultBlockState());

        // Windows in the side walls, between the pedestals: panes joined along the wall.
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.PipeBlock.PROPERTY_BY_DIRECTION.get(look), true)
                .setValue(net.minecraft.world.level.block.PipeBlock.PROPERTY_BY_DIRECTION.get(look.getOpposite()), true);
        for (int u : new int[] {-side, side})
            for (int v : new int[] {2, 6})
                for (int y = 2; y <= 3; y++)
                    room.set(u, v, y, pane);

        // A rug in the middle, round a bare stone spot for the big globe: drawn two blocks wide, its base would sink
        // into carpet around it.
        for (int u = -2; u <= 2; u++)
            for (int v = 3; v <= 7; v++)
            {
                if (Math.abs(u) <= 1 && Math.abs(v - 5) <= 1)
                {
                    room.set(u, v, 0, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                    continue;
                }
                room.set(u, v, 1, (Math.abs(u) == 2 || v == 3 || v == 7 ? Blocks.BROWN_CARPET : Blocks.RED_CARPET).defaultBlockState());
            }

        // Lanterns hanging from the beams.
        for (int u : new int[] {-3, 3})
            for (int v : new int[] {3, 7})
            {
                room.set(u, v, HEIGHT, Blocks.CHAIN.defaultBlockState());
                room.set(u, v, HEIGHT - 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }

        // Along the back wall: a low counter with five shelves side by side, candles at its ends.
        GlobeKind[] onShelves = {GlobeKind.SNOW_GLOBE, GlobeKind.TERRARIUM, GlobeKind.STORM_GLOBE, GlobeKind.SHIP_BOTTLE,
                GlobeKind.DESERT_GLOBE, GlobeKind.AQUARIUM, GlobeKind.STAR_GLOBE, GlobeKind.NETHER_JAR, GlobeKind.SNOW_GLOBE, GlobeKind.TERRARIUM};
        for (int u = -side + 1; u <= side - 1; u++)
            room.set(u, back - 1, 1, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                    net.minecraft.world.level.block.state.properties.SlabType.TOP));
        for (int i = 0; i < 5; i++)
        {
            int u = i - 2;
            room.set(u, back - 1, 2, WorldsInGlassMod.SHELF.get().defaultBlockState().setValue(GlobeShelfBlock.FACING, look.getOpposite()));
            if (level.getBlockEntity(room.at(u, back - 1, 2)) instanceof GlobeShelfBlockEntity shelf)
                for (int slot = 0; slot < 2; slot++)
                    shelf.set(slot, globeItem(server, onShelves[i * 2 + slot]));
        }
        for (int u : new int[] {-4, 4})
            room.set(u, back - 1, 2, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true));

        // Little pedestals along the side walls, under the windows' light, each globe side-on to the room.
        GlobeKind[] left = {GlobeKind.TERRARIUM, GlobeKind.STAR_GLOBE, GlobeKind.AQUARIUM};
        GlobeKind[] rightSide = {GlobeKind.STORM_GLOBE, GlobeKind.DESERT_GLOBE, GlobeKind.NETHER_JAR};
        int[] rows = {3, 5, 7};
        for (int i = 0; i < 3; i++)
        {
            room.set(-HALF_WIDTH, rows[i], 1, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            placeGlobe(server, level, room.at(-HALF_WIDTH, rows[i], 2), left[i], right.getOpposite().getClockWise(), 0);
            room.set(HALF_WIDTH, rows[i], 1, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            placeGlobe(server, level, room.at(HALF_WIDTH, rows[i], 2), rightSide[i], right.getClockWise(), 0);
        }

        // The centrepiece: a big snow globe on the rug.
        room.set(0, 5, 1, Blocks.AIR.defaultBlockState());
        placeGlobe(server, level, room.at(0, 5, 1), GlobeKind.SNOW_GLOBE, look.getClockWise(), 48);
        if (level.getBlockEntity(room.at(0, 5, 1)) instanceof GlobeBlockEntity centrepiece)
            centrepiece.setName(Component.literal("The Long Winter"));

        player.sendSystemMessage(Component.literal("Built a globe gallery in front of you (" + look.getName() + "). Look in through the doorway."));
        return 1;
    }

    /** Places a globe with its starter scene; {@code size} above 16 makes it bigger (and drawn bigger). */
    private static void placeGlobe(MinecraftServer server, ServerLevel level, BlockPos at, GlobeKind kind, Direction facing, int size)
    {
        level.setBlock(at, WorldsInGlassMod.globe(kind).get().defaultBlockState().setValue(GlobeBlock.FACING, facing), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(at) instanceof GlobeBlockEntity globe))
            return;
        int id = globe.ensureGlobe(server, true);
        if (size > GlobeSpace.DEFAULT_SIZE)
        {
            GlobeSpace.resize(server, id, size);
            fillBelow(server, id);
        }
        globe.updateDisplaySize();
        globe.refreshExtras();
        globe.setName(Component.literal(sceneName(kind)));
        level.sendBlockUpdated(at, globe.getBlockState(), globe.getBlockState(), Block.UPDATE_CLIENTS);
    }

    /**
     * A starter scene grown into a bigger round globe floats in the middle of it; fill the bowl underneath with earth
     * (stone deeper down), so there's real ground all the way to the glass.
     */
    private static void fillBelow(MinecraftServer server, int id)
    {
        ServerLevel level = server.getLevel(GlobeSpace.LEVEL);
        if (level == null)
            return;
        GlobeSavedData data = GlobeSavedData.get(server);
        GlobeKind kind = data.kindOf(id);
        int size = data.sizeOf(id);
        BlockPos min = GlobeSpace.interiorMin(server, id);
        int[] ground = {size};
        GlobeSpace.forEachBlock(level, min, size, (x, y, z, state, pos) -> {
            if (kind.inside(x, y, z, size))
                ground[0] = Math.min(ground[0], y);
        });
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                for (int y = 0; y < ground[0]; y++)
                    if (kind.inside(x, y, z, size))
                        level.setBlock(min.offset(x, y, z), (y < ground[0] - 3 ? Blocks.STONE : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
        GlobeSpace.refreshSnapshot(server, id);
    }

    /** What each kind's starter scene is called, for its name plate. */
    private static String sceneName(GlobeKind kind)
    {
        return switch (kind)
        {
            case SNOW_GLOBE -> "Winter Cabin";
            case TERRARIUM -> "Mossy Garden";
            case STORM_GLOBE -> "The Lighthouse";
            case SHIP_BOTTLE -> "The Wanderer";
            case DESERT_GLOBE -> "Oasis";
            case AQUARIUM -> "Coral Reef";
            case STAR_GLOBE -> "Starlit Camp";
            case NETHER_JAR -> "Ember Cavern";
        };
    }

    /** A globe item with a fresh starter scene, as if picked up after placing it. */
    private static ItemStack globeItem(MinecraftServer server, GlobeKind kind)
    {
        int id = GlobeSpace.create(server, kind, true);
        GlobeSavedData data = GlobeSavedData.get(server);
        ItemStack stack = new ItemStack(WorldsInGlassMod.GLOBE_ITEMS.get(kind).get());
        GlobeSavedData.Entry entry = data.get(id);
        CompoundTag tag = new CompoundTag();
        tag.putInt("GlobeId", id);
        if (entry != null)
        {
            tag.put("Snapshot", entry.snapshot().toTag());
            tag.putLong("SnapshotVersion", entry.version());
        }
        tag.putInt("Phase", data.phaseOf(id));
        tag.putInt("Season", data.seasonOf(id));
        stack.set(WorldsInGlassMod.GLOBE_DATA.get(), CustomData.of(tag));
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(sceneName(kind)));
        return stack;
    }
}
