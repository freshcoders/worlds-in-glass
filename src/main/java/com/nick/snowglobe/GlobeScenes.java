package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.function.Consumer;

/** The little scene a new globe starts with, so there's something to look at straight away. */
final class GlobeScenes
{
    private GlobeScenes() {}

    static void build(ServerLevel level, BlockPos min, GlobeKind kind)
    {
        Scene scene = new Scene(level, min, kind);
        switch (kind)
        {
            case TERRARIUM -> mossyGarden(scene);
            case STORM_GLOBE -> stormyLighthouse(scene);
            case SHIP_BOTTLE -> shipInBottle(scene);
            default -> snowyCabin(scene);
        }
    }

    /** Writes blocks in globe-local coordinates, ignoring anything outside the build space. */
    private record Scene(ServerLevel level, BlockPos min, GlobeKind kind)
    {
        void set(int x, int y, int z, BlockState state)
        {
            if (kind.inside(x, y, z, GlobeSpace.DEFAULT_SIZE))
                level.setBlock(min.offset(x, y, z), state, Block.UPDATE_CLIENTS);
        }

        BlockState get(int x, int y, int z)
        {
            return level.getBlockState(min.offset(x, y, z));
        }

        boolean empty(int x, int y, int z)
        {
            return kind.inside(x, y, z, GlobeSpace.DEFAULT_SIZE) && get(x, y, z).isAir();
        }
    }

    // ---- Snow globe: a cabin, spruce trees and a lamp post on snowy ground, with a snow golem and a fox ----

    private static void snowyCabin(Scene s)
    {
        final int g = 3; // ground level
        BlockState snowyGrass = Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyDirtBlock.SNOWY, true);
        for (int x = 0; x < GlobeSpace.DEFAULT_SIZE; x++)
            for (int z = 0; z < GlobeSpace.DEFAULT_SIZE; z++)
                for (int y = 0; y <= g; y++)
                    s.set(x, y, z, y == g ? snowyGrass : Blocks.DIRT.defaultBlockState());

        // Cabin: spruce walls with log corners, a door gap facing south, two windows and a lantern inside.
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        for (int x = 8; x <= 11; x++)
            for (int z = 5; z <= 8; z++)
            {
                s.set(x, g, z, planks);
                boolean corner = (x == 8 || x == 11) && (z == 5 || z == 8);
                boolean wall = x == 8 || x == 11 || z == 5 || z == 8;
                if (wall)
                    for (int y = g + 1; y <= g + 2; y++)
                        s.set(x, y, z, corner ? log : planks);
            }
        s.set(9, g + 1, 8, Blocks.AIR.defaultBlockState());
        s.set(9, g + 2, 8, Blocks.AIR.defaultBlockState());
        s.set(8, g + 2, 6, Blocks.GLASS.defaultBlockState());
        s.set(11, g + 2, 7, Blocks.GLASS.defaultBlockState());
        s.set(10, g + 1, 6, Blocks.LANTERN.defaultBlockState());

        // Pitched roof running east-west, with filled gable ends and a stone chimney.
        BlockState stairs = Blocks.DARK_OAK_STAIRS.defaultBlockState();
        for (int x = 7; x <= 12; x++)
        {
            s.set(x, g + 3, 4, stairs.setValue(StairBlock.FACING, Direction.SOUTH));
            s.set(x, g + 3, 9, stairs.setValue(StairBlock.FACING, Direction.NORTH));
            s.set(x, g + 4, 5, stairs.setValue(StairBlock.FACING, Direction.SOUTH));
            s.set(x, g + 4, 8, stairs.setValue(StairBlock.FACING, Direction.NORTH));
            s.set(x, g + 5, 6, Blocks.DARK_OAK_SLAB.defaultBlockState());
            s.set(x, g + 5, 7, Blocks.DARK_OAK_SLAB.defaultBlockState());
        }
        for (int x : new int[] {8, 11})
        {
            for (int z = 5; z <= 8; z++)
                s.set(x, g + 3, z, planks);
            s.set(x, g + 4, 6, planks);
            s.set(x, g + 4, 7, planks);
        }
        for (int y = g + 4; y <= g + 6; y++)
            s.set(10, y, 5, Blocks.COBBLESTONE.defaultBlockState());

        // A path from the door.
        for (int z = 9; z <= 12; z++)
            s.set(9, g, z, Blocks.DIRT_PATH.defaultBlockState());

        // Spruce tree.
        spruce(s, 4, g + 1, 10, 5);
        spruce(s, 12, g + 1, 11, 3);

        // Residents: a snow golem (the snowman, walking about) and a snow fox.
        spawn(s, EntityType.SNOW_GOLEM, 5.5, g + 1, 5.5, mob -> {});
        spawn(s, EntityType.FOX, 10.5, g + 1, 11.5, mob -> ((Fox) mob).setVariant(Fox.Type.SNOW));

        // Lamp post by the path.
        s.set(7, g + 1, 11, Blocks.SPRUCE_FENCE.defaultBlockState());
        s.set(7, g + 2, 11, Blocks.SPRUCE_FENCE.defaultBlockState());
        s.set(7, g + 3, 11, Blocks.LANTERN.defaultBlockState());

        // A thin blanket of snow on all open grass.
        for (int x = 0; x < GlobeSpace.DEFAULT_SIZE; x++)
            for (int z = 0; z < GlobeSpace.DEFAULT_SIZE; z++)
                if (s.get(x, g, z).is(Blocks.GRASS_BLOCK) && s.empty(x, g + 1, z))
                    s.set(x, g + 1, z, Blocks.SNOW.defaultBlockState());
    }

    private static void spruce(Scene s, int x, int y, int z, int height)
    {
        BlockState leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int i = 0; i < height; i++)
            s.set(x, y + i, z, Blocks.SPRUCE_LOG.defaultBlockState());
        // Tiers of leaves: wide rounded squares low down, alternating with narrow crosses, up to a single tip.
        for (int i = 1; i < height; i++)
        {
            boolean wide = height - i >= 3 && i % 2 == 1;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                {
                    int dist = Math.abs(dx) + Math.abs(dz);
                    boolean leaf = wide ? dist <= 3 : dist <= 1;
                    if (leaf && dist > 0 && s.empty(x + dx, y + i, z + dz))
                        s.set(x + dx, y + i, z + dz, leaves);
                }
        }
        s.set(x, y + height, z, leaves);
    }

    // ---- Terrarium: mossy ground with a mound, an azalea tree, a fallen log, rocks and plants ----

    private static void mossyGarden(Scene s)
    {
        RandomSource random = RandomSource.create(20261015L);
        int size = GlobeSpace.DEFAULT_SIZE;
        int[][] ground = new int[size][size];
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                // Flat ground at 2, rising into a mound in the back-left corner.
                double d = Math.hypot(x - 4, z - 11);
                int h = 2 + (d < 2.5 ? 2 : d < 4.5 ? 1 : 0);
                ground[x][z] = h;
                for (int y = 0; y <= h; y++)
                {
                    BlockState state = y == h ? Blocks.MOSS_BLOCK.defaultBlockState()
                            : y == h - 1 && random.nextInt(3) == 0 ? Blocks.ROOTED_DIRT.defaultBlockState()
                            : Blocks.DIRT.defaultBlockState();
                    s.set(x, y, z, state);
                }
            }

        // Azalea tree on the mound.
        int tx = 4, tz = 11, ty = ground[tx][tz] + 1;
        for (int i = 0; i < 4; i++)
            s.set(tx, ty + i, tz, Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -2; dz <= 2; dz++)
                {
                    if (dx * dx + dy * dy * 2 + dz * dz > 5 || !s.empty(tx + dx, ty + 4 + dy, tz + dz))
                        continue;
                    Block leaf = random.nextInt(3) == 0 ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.AZALEA_LEAVES;
                    s.set(tx + dx, ty + 4 + dy, tz + dz, leaf.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }

        // Fallen log, with mushrooms growing beside it.
        for (int x = 9; x <= 12; x++)
            s.set(x, ground[x][12] + 1, 12, Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        s.set(10, ground[10][11] + 1, 11, Blocks.RED_MUSHROOM.defaultBlockState());
        s.set(12, ground[12][13] + 1, 13, Blocks.BROWN_MUSHROOM.defaultBlockState());

        // A cluster of mossy rocks.
        int[][] rocks = {{12, 4, 0}, {13, 4, 0}, {12, 5, 0}, {13, 3, 0}, {12, 4, 1}};
        for (int[] r : rocks)
            s.set(r[0], ground[r[0]][r[1]] + 1 + r[2], r[1],
                    (r[2] == 1 ? Blocks.MOSSY_COBBLESTONE : random.nextBoolean() ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState());

        // Scatter plants over the open moss.
        BlockState[] plants = {
                Blocks.SHORT_GRASS.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), Blocks.FERN.defaultBlockState(),
                Blocks.MOSS_CARPET.defaultBlockState(), Blocks.AZALEA.defaultBlockState(), Blocks.FLOWERING_AZALEA.defaultBlockState(),
                Blocks.BLUE_ORCHID.defaultBlockState(), Blocks.AZURE_BLUET.defaultBlockState(), Blocks.ALLIUM.defaultBlockState(),
                Blocks.PINK_PETALS.defaultBlockState().setValue(BlockStateProperties.FLOWER_AMOUNT, 4),
        };
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                int y = ground[x][z] + 1;
                if (s.empty(x, y, z) && random.nextInt(100) < 45)
                    s.set(x, y, z, plants[random.nextInt(plants.length)]);
            }

        // Residents: two frogs and a rabbit.
        spawn(s, EntityType.FROG, 7.5, ground[7][6] + 1, 6.5, mob -> {});
        spawn(s, EntityType.FROG, 9.5, ground[9][9] + 1, 9.5, mob -> {});
        spawn(s, EntityType.RABBIT, 11.5, ground[11][7] + 1, 7.5, mob -> {});
    }

    // ---- Storm globe: a lighthouse on a rocky island in a dark sea, with cod swimming round it ----

    private static void stormyLighthouse(Scene s)
    {
        RandomSource random = RandomSource.create(1987L);
        final int sea = 5; // water surface
        int size = GlobeSpace.DEFAULT_SIZE;
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                double d = Math.hypot(x + 0.5 - 8, z + 0.5 - 8);
                int island = d < 2.5 ? 7 : d < 3.5 ? 6 : d < 4.6 ? sea : -1;
                for (int y = 0; y < size; y++)
                {
                    BlockState state;
                    if (y <= island)
                        state = y < island ? (random.nextInt(4) == 0 ? Blocks.COBBLESTONE : Blocks.STONE).defaultBlockState()
                                : d < 3.5 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.SAND.defaultBlockState();
                    else if (y <= 2)
                        state = (random.nextBoolean() ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
                    else if (y <= sea)
                        state = Blocks.WATER.defaultBlockState();
                    else
                        continue;
                    s.set(x, y, z, state);
                }
            }

        // Striped lighthouse: a plus-shaped tower, a lamp room with a sea lantern, and a red cap.
        int[][] plus = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int y = 8; y <= 13; y++)
        {
            Block stripe = (y - 8) / 2 % 2 == 0 ? Blocks.WHITE_CONCRETE : Blocks.RED_CONCRETE;
            for (int[] p : plus)
                s.set(8 + p[0], y, 8 + p[1], stripe.defaultBlockState());
        }
        for (int[] p : plus)
            s.set(8 + p[0], 14, 8 + p[1], (p[0] == 0 && p[1] == 0 ? Blocks.SEA_LANTERN : Blocks.GLASS).defaultBlockState());
        s.set(8, 15, 8, Blocks.RED_CONCRETE.defaultBlockState());

        // A few rocks sticking out of the water.
        s.set(3, sea + 1, 6, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        s.set(12, sea + 1, 11, Blocks.COBBLESTONE.defaultBlockState());

        spawn(s, EntityType.COD, 3.5, 3, 9.5, mob -> {});
        spawn(s, EntityType.COD, 12.5, 3, 5.5, mob -> {});
        spawn(s, EntityType.COD, 8.5, 3, 13.5, mob -> {});
    }

    // ---- Ship in a bottle: a sailing ship on a strip of sea, with tropical fish below ----

    private static void shipInBottle(Scene s)
    {
        int size = GlobeSpace.DEFAULT_SIZE;
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                for (int y = 0; y <= 6; y++)
                    s.set(x, y, z, (y <= 3 ? Blocks.SAND : Blocks.WATER).defaultBlockState());

        BlockState hull = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState keel = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        // Keel in the water, then the deck, pointed at bow (east) and stern (west).
        for (int x = 4; x <= 11; x++)
        {
            s.set(x, 6, 8, keel);
            if (x >= 5 && x <= 10)
            {
                s.set(x, 6, 7, keel);
                s.set(x, 6, 9, keel);
            }
            for (int z = 7; z <= 9; z++)
                s.set(x, 7, z, hull);
            s.set(x, 8, 7, Blocks.SPRUCE_SLAB.defaultBlockState());
            s.set(x, 8, 9, Blocks.SPRUCE_SLAB.defaultBlockState());
        }
        s.set(3, 7, 8, hull);
        s.set(12, 7, 8, hull);
        s.set(13, 8, 8, Blocks.SPRUCE_FENCE.defaultBlockState()); // bowsprit
        // Stern cabin.
        for (int z = 7; z <= 9; z++)
        {
            s.set(4, 8, z, hull);
            s.set(5, 8, z, hull);
            s.set(4, 9, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
            s.set(5, 9, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
        }

        // Two masts with white sails across the ship, and a red flag on top of the main mast.
        BlockState mast = Blocks.SPRUCE_FENCE.defaultBlockState();
        BlockState sail = Blocks.WHITE_WOOL.defaultBlockState();
        for (int y = 8; y <= 12; y++)
            s.set(8, y, 8, mast);
        for (int y = 8; y <= 11; y++)
            s.set(11, y, 8, mast);
        for (int z = 6; z <= 10; z++)
            if (z != 8)
            {
                for (int y = 9; y <= 11; y++)
                    s.set(8, y, z, sail);
                for (int y = 9; y <= 10; y++)
                    s.set(11, y, z, sail);
            }
        s.set(8, 12, 8, Blocks.RED_WOOL.defaultBlockState());

        spawn(s, EntityType.TROPICAL_FISH, 2.5, 4.2, 8.5, mob -> {});
        spawn(s, EntityType.TROPICAL_FISH, 13.5, 4.2, 7.5, mob -> {});
    }

    /** Puts a mob in the globe that stays for good (it never despawns), with a natural look (variant, colours). */
    private static void spawn(Scene s, EntityType<? extends Mob> type, double x, double y, double z, Consumer<Mob> setup)
    {
        Mob mob = type.create(s.level());
        if (mob == null)
            return;
        mob.moveTo(s.min().getX() + x, s.min().getY() + y, s.min().getZ() + z, s.level().random.nextFloat() * 360, 0);
        mob.finalizeSpawn(s.level(), s.level().getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT, null);
        mob.setPersistenceRequired();
        setup.accept(mob);
        s.level().addFreshEntity(mob);
    }
}
