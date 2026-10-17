package com.nick.worldsinglass;

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
            case DESERT_GLOBE -> desertOasis(scene);
            case AQUARIUM -> coralReef(scene);
            case STAR_GLOBE -> starlitCamp(scene);
            case NETHER_JAR -> lavaCavern(scene);
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

    // ---- Desert globe: rolling dunes, a little stepped pyramid, and an oasis with a palm, at sunset ----

    private static void desertOasis(Scene s)
    {
        RandomSource random = RandomSource.create(1922L);
        int size = GlobeSpace.DEFAULT_SIZE;
        int[][] ground = new int[size][size];
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                // Dunes: gentle waves in the sand, around a ground level of 3.
                int h = 3 + (int) Math.round(Math.sin(x * 0.55 + z * 0.25) * 0.8 + Math.cos(z * 0.6) * 0.5);
                ground[x][z] = h;
                for (int y = 0; y <= h; y++)
                    s.set(x, y, z, (y < h - 1 ? Blocks.SANDSTONE : Blocks.SAND).defaultBlockState());
            }

        // A stepped pyramid in the back corner.
        int px = 10, pz = 4, base = 3;
        for (int step = 0; step < 4; step++)
            for (int x = px - 3 + step; x <= px + 3 - step; x++)
                for (int z = pz - 3 + step; z <= pz + 3 - step; z++)
                    s.set(x, base + 1 + step, z, (step == 3 ? Blocks.CHISELED_SANDSTONE : Blocks.SMOOTH_SANDSTONE).defaultBlockState());

        // The oasis: a pool in a dip, ringed with grass, with a palm leaning over it.
        int ox = 5, oz = 10;
        for (int x = ox - 3; x <= ox + 3; x++)
            for (int z = oz - 3; z <= oz + 3; z++)
            {
                double d = Math.hypot(x - ox, z - oz);
                if (d < 2.2)
                {
                    for (int y = 2; y <= ground[x][z]; y++)
                        s.set(x, y, z, Blocks.AIR.defaultBlockState());
                    s.set(x, 1, z, Blocks.SAND.defaultBlockState());
                    s.set(x, 2, z, Blocks.WATER.defaultBlockState());
                    ground[x][z] = 1;
                }
                else if (d < 3.4 && s.get(x, ground[x][z], z).is(Blocks.SAND))
                {
                    s.set(x, ground[x][z], z, Blocks.GRASS_BLOCK.defaultBlockState());
                    if (random.nextInt(3) == 0 && s.empty(x, ground[x][z] + 1, z))
                        s.set(x, ground[x][z] + 1, z, Blocks.SHORT_GRASS.defaultBlockState());
                }
            }
        s.set(ox - 1, 2, oz + 1, Blocks.LILY_PAD.defaultBlockState());
        // The palm: a jungle trunk bending one way, with a crown of leaves.
        int tx = ox + 2, tz = oz - 2, ty = ground[tx][tz] + 1;
        int[][] trunk = {{0, 0}, {0, 0}, {0, 0}, {-1, 0}, {-1, 0}};
        int cx = tx;
        for (int i = 0; i < trunk.length; i++)
        {
            cx += trunk[i][0];
            s.set(cx, ty + i, tz, Blocks.JUNGLE_LOG.defaultBlockState());
        }
        int top = ty + trunk.length;
        BlockState palm = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        s.set(cx, top, tz, palm);
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
        {
            s.set(cx + d[0], top, tz + d[1], palm);
            s.set(cx + d[0] * 2, top - 1, tz + d[1] * 2, palm);
        }
        s.set(cx + 1, top - 1, tz + 1, Blocks.COCOA.defaultBlockState());

        // Cacti and dry bushes dotted over the dunes.
        int[][] cacti = {{2, 3}, {13, 12}, {8, 13}};
        for (int[] c : cacti)
            for (int i = 1; i <= 2 + random.nextInt(2); i++)
                if (s.empty(c[0], ground[c[0]][c[1]] + i, c[1]))
                    s.set(c[0], ground[c[0]][c[1]] + i, c[1], Blocks.CACTUS.defaultBlockState());
        for (int i = 0; i < 9; i++)
        {
            int x = random.nextInt(size), z = random.nextInt(size);
            if (s.get(x, ground[x][z], z).is(Blocks.SAND) && s.empty(x, ground[x][z] + 1, z))
                s.set(x, ground[x][z] + 1, z, Blocks.DEAD_BUSH.defaultBlockState());
        }

        // Residents: a camel resting by the water, and a desert rabbit.
        spawn(s, EntityType.CAMEL, 9.5, ground[9][11] + 1, 11.5, mob -> {});
        spawn(s, EntityType.RABBIT, 3.5, ground[3][6] + 1, 6.5,
                mob -> ((net.minecraft.world.entity.animal.Rabbit) mob).setVariant(net.minecraft.world.entity.animal.Rabbit.Variant.GOLD));
    }

    // ---- Aquarium: a coral reef on a sandy floor, with kelp, a sunken chest, fish and an axolotl ----

    private static void coralReef(Scene s)
    {
        RandomSource random = RandomSource.create(1888L);
        int size = GlobeSpace.DEFAULT_SIZE;
        final int surface = 13;
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                int floor = 1 + (Math.hypot(x - 4, z - 11) < 3 ? 1 : 0);
                for (int y = 0; y <= surface; y++)
                    s.set(x, y, z, y <= floor ? (y == floor && random.nextInt(5) == 0 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState()
                            : Blocks.WATER.defaultBlockState());
            }

        // Coral heads, with coral and fans growing on and around them.
        Block[] corals = {Blocks.BRAIN_CORAL_BLOCK, Blocks.TUBE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK};
        Block[] plants = {Blocks.BRAIN_CORAL, Blocks.TUBE_CORAL, Blocks.FIRE_CORAL, Blocks.HORN_CORAL, Blocks.BUBBLE_CORAL,
                Blocks.BRAIN_CORAL_FAN, Blocks.TUBE_CORAL_FAN, Blocks.FIRE_CORAL_FAN, Blocks.HORN_CORAL_FAN, Blocks.BUBBLE_CORAL_FAN};
        int[][] heads = {{10, 10, 3}, {12, 5, 2}, {5, 4, 2}, {8, 13, 1}};
        for (int[] h : heads)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy < h[2]; dy++)
                        if (Math.abs(dx) + Math.abs(dz) + dy <= h[2] && random.nextInt(5) > 0)
                            s.set(h[0] + dx, 2 + dy, h[1] + dz, corals[random.nextInt(corals.length)].defaultBlockState());
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                for (int y = 2; y < 7; y++)
                    if (s.get(x, y, z).is(Blocks.WATER) && !s.get(x, y - 1, z).is(Blocks.WATER) && !s.get(x, y - 1, z).isAir()
                            && random.nextInt(100) < 30)
                    {
                        s.set(x, y, z, plants[random.nextInt(plants.length)].defaultBlockState());
                        break;
                    }

        // Kelp swaying up towards the surface, and seagrass on the open sand.
        for (int[] k : new int[][] {{2, 2}, {3, 8}, {13, 13}, {14, 8}})
        {
            int height = 6 + random.nextInt(4);
            for (int i = 0; i < height; i++)
                s.set(k[0], 2 + i, k[1], (i == height - 1 ? Blocks.KELP : Blocks.KELP_PLANT).defaultBlockState());
        }
        for (int i = 0; i < 26; i++)
        {
            int x = random.nextInt(size), z = random.nextInt(size);
            for (int y = 2; y < 5; y++)
                if (s.get(x, y, z).is(Blocks.WATER) && s.get(x, y - 1, z).is(Blocks.SAND))
                {
                    s.set(x, y, z, Blocks.SEAGRASS.defaultBlockState());
                    break;
                }
        }
        // A sunken chest in the sand, and sea pickles glowing beside it.
        s.set(4, 3, 11, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        s.set(5, 3, 12, Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 3));
        s.set(3, 3, 10, Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 2));

        spawn(s, EntityType.TROPICAL_FISH, 6.5, 8, 7.5, mob -> {});
        spawn(s, EntityType.TROPICAL_FISH, 11.5, 9, 3.5, mob -> {});
        spawn(s, EntityType.TROPICAL_FISH, 3.5, 7, 13.5, mob -> {});
        spawn(s, EntityType.AXOLOTL, 9.5, 4, 8.5, mob -> {});
    }

    // ---- Star globe: a camp on a hilltop under the night sky, with a tent, a campfire and amethyst ----

    private static void starlitCamp(Scene s)
    {
        RandomSource random = RandomSource.create(1610L);
        int size = GlobeSpace.DEFAULT_SIZE;
        int[][] ground = new int[size][size];
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                // A low, rounded hill.
                double d = Math.hypot(x + 0.5 - 8, z + 0.5 - 8);
                int h = 3 + (d < 3 ? 2 : d < 5.5 ? 1 : 0);
                ground[x][z] = h;
                for (int y = 0; y <= h; y++)
                    s.set(x, y, z, (y == h ? Blocks.GRASS_BLOCK : y < h - 2 ? Blocks.STONE : Blocks.DIRT).defaultBlockState());
            }

        // A little white tent: a ridge of wool with sloped sides.
        BlockState wool = Blocks.WHITE_WOOL.defaultBlockState();
        BlockState stairs = Blocks.BIRCH_STAIRS.defaultBlockState();
        int g = ground[7][7];
        for (int x = 6; x <= 8; x++)
        {
            s.set(x, g + 1, 5, stairs.setValue(StairBlock.FACING, Direction.SOUTH));
            s.set(x, g + 1, 7, stairs.setValue(StairBlock.FACING, Direction.NORTH));
            s.set(x, g + 2, 6, wool);
        }
        s.set(6, g + 1, 6, wool);

        // The campfire, with logs to sit on.
        s.set(9, g + 1, 9, Blocks.CAMPFIRE.defaultBlockState());
        s.set(11, ground[11][9] + 1, 9, Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        s.set(9, ground[9][11] + 1, 11, Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));

        // Amethyst crystals poking out of the hillside, glowing faintly.
        for (int[] a : new int[][] {{3, 4}, {12, 4}, {4, 12}, {13, 12}})
        {
            int y = ground[a[0]][a[1]];
            s.set(a[0], y, a[1], Blocks.AMETHYST_BLOCK.defaultBlockState());
            s.set(a[0], y + 1, a[1], Blocks.AMETHYST_CLUSTER.defaultBlockState());
        }

        spruce(s, 3, ground[3][8] + 1, 8, 4);
        // Flowers in the grass.
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                if (s.get(x, ground[x][z], z).is(Blocks.GRASS_BLOCK) && s.empty(x, ground[x][z] + 1, z) && random.nextInt(100) < 18)
                    s.set(x, ground[x][z] + 1, z, (random.nextBoolean() ? Blocks.SHORT_GRASS : Blocks.LILY_OF_THE_VALLEY).defaultBlockState());

        // A fox, curled up by the fire.
        spawn(s, EntityType.FOX, 10.5, g + 1, 7.5, mob -> {});
    }

    // ---- Nether jar: a cavern of netherrack and basalt around a lava pool, with fungi and a strider ----

    private static void lavaCavern(Scene s)
    {
        RandomSource random = RandomSource.create(1321L);
        int size = GlobeSpace.DEFAULT_SIZE;
        int[][] ground = new int[size][size];
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                double d = Math.hypot(x + 0.5 - 8, z + 0.5 - 8);
                // A lava pool in the middle, the floor rising towards the glass.
                int h = d < 3.2 ? 1 : 2 + (d > 5 ? 1 : 0);
                ground[x][z] = h;
                for (int y = 0; y <= h; y++)
                    s.set(x, y, z, (random.nextInt(9) == 0 ? Blocks.NETHER_QUARTZ_ORE : Blocks.NETHERRACK).defaultBlockState());
                if (d < 3.2)
                    s.set(x, 2, z, Blocks.LAVA.defaultBlockState());
            }

        // Basalt pillars, one with glowstone hanging from the top.
        for (int[] p : new int[][] {{3, 4, 7}, {12, 11, 9}, {11, 3, 5}})
        {
            for (int y = ground[p[0]][p[1]] + 1; y <= ground[p[0]][p[1]] + p[2]; y++)
                s.set(p[0], y, p[1], Blocks.BASALT.defaultBlockState());
            s.set(p[0], ground[p[0]][p[1]] + p[2] + 1, p[1], Blocks.GLOWSTONE.defaultBlockState());
        }

        // A crimson patch and a warped patch, with fungi and roots.
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
            {
                int y = ground[x][z];
                if (!s.get(x, y, z).is(Blocks.NETHERRACK) || !s.empty(x, y + 1, z))
                    continue;
                boolean crimson = x < 7 && z > 8, warped = x > 9 && z > 6 && z < 12;
                if (!crimson && !warped)
                    continue;
                s.set(x, y, z, (crimson ? Blocks.CRIMSON_NYLIUM : Blocks.WARPED_NYLIUM).defaultBlockState());
                if (random.nextInt(100) < 40)
                    s.set(x, y + 1, z, (crimson ? (random.nextBoolean() ? Blocks.CRIMSON_ROOTS : Blocks.CRIMSON_FUNGUS)
                            : (random.nextBoolean() ? Blocks.WARPED_ROOTS : Blocks.WARPED_FUNGUS)).defaultBlockState());
            }

        // A bit of ruined fortress: a nether brick arch, with a soul fire burning beside it.
        for (int y = 3; y <= 6; y++)
        {
            s.set(6, y, 2, Blocks.NETHER_BRICKS.defaultBlockState());
            s.set(9, y, 2, Blocks.NETHER_BRICKS.defaultBlockState());
        }
        for (int x = 6; x <= 9; x++)
            s.set(x, 7, 2, Blocks.NETHER_BRICKS.defaultBlockState());
        s.set(7, 7, 3, Blocks.NETHER_BRICK_FENCE.defaultBlockState());
        s.set(12, ground[12][13], 13, Blocks.SOUL_SAND.defaultBlockState());
        s.set(12, ground[12][13] + 1, 13, Blocks.SOUL_FIRE.defaultBlockState());

        spawn(s, EntityType.STRIDER, 8.5, 2.5, 8.5, mob -> {});
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
