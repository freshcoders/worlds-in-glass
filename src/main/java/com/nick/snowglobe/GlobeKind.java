package com.nick.snowglobe;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The kinds of globe: what they look like from outside and the shape of their build space inside. */
public enum GlobeKind
{
    SNOW_GLOBE("snow_globe", Space.SPHERE, ballOnPedestal()),
    TERRARIUM("terrarium", Space.CUBE, Block.box(2, 0, 2, 14, 12, 14)),
    STORM_GLOBE("storm_globe", Space.SPHERE, ballOnPedestal()),
    SHIP_BOTTLE("ship_bottle", Space.BOTTLE, Block.box(1.5, 0, 3.5, 15.6, 9.3, 12.5)),
    DESERT_GLOBE("desert_globe", Space.SPHERE, ballOnPedestal()),
    AQUARIUM("aquarium", Space.CUBE, Block.box(2, 0, 2, 14, 12, 14)),
    STAR_GLOBE("star_globe", Space.SPHERE, ballOnPedestal()),
    NETHER_JAR("nether_jar", Space.JAR, Block.box(3, 0, 3, 13, 14, 13));

    /** The shape of the build space inside its size³ cube. */
    public enum Space
    {
        /** A ball touching the cube's sides. */
        SPHERE,
        /** The whole cube. */
        CUBE,
        /** A bottle lying along the x axis: a cylinder about two thirds as wide as it is long. */
        BOTTLE,
        /** A jar standing upright: a cylinder four fifths as wide as it is tall. */
        JAR;

        /** True for spaces with a flat floor at the bottom of the cube (they grow upwards from it). */
        public boolean hasFloor()
        {
            return this == CUBE || this == JAR;
        }
    }

    private final String name;
    public final Space space;
    public final VoxelShape shape;

    GlobeKind(String name, Space space, VoxelShape shape)
    {
        this.name = name;
        this.space = space;
        this.shape = shape;
    }

    private static VoxelShape ballOnPedestal()
    {
        return Shapes.or(
                Block.box(4, 0, 4, 12, 2.75, 12),          // pedestal
                Block.box(3.5, 2, 3.5, 12.5, 11, 12.5));   // glass ball
    }

    public String getName()
    {
        return name;
    }

    public static GlobeKind byName(String name)
    {
        for (GlobeKind kind : values())
            if (kind.name.equals(name))
                return kind;
        return SNOW_GLOBE;
    }

    /** Radius of a bottle's build space, in blocks, for a globe of the given size (5.5 at size 16). */
    public static double bottleRadius(int size)
    {
        return size * 11 / 32.0;
    }

    /** Radius of a jar's build space, in blocks, for a globe of the given size. */
    public static double jarRadius(int size)
    {
        return size * 0.4;
    }

    /** True for the cells of the size³ cube that belong to this kind's build space. */
    public boolean inside(int x, int y, int z, int size)
    {
        if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size)
            return false;
        double c = size / 2.0;
        double dx = x + 0.5 - c, dy = y + 0.5 - c, dz = z + 0.5 - c;
        return switch (space)
        {
            case CUBE -> true;
            case SPHERE -> dx * dx + dy * dy + dz * dz <= c * c;
            case BOTTLE -> dy * dy + dz * dz <= bottleRadius(size) * bottleRadius(size);
            case JAR -> dx * dx + dz * dz <= jarRadius(size) * jarRadius(size);
        };
    }

    /** True if a box of this many blocks fits in this kind's build space at the given size. */
    public boolean fits(int sx, int sy, int sz, int size)
    {
        return switch (space)
        {
            case CUBE -> sx <= size && sy <= size && sz <= size;
            case SPHERE -> Math.sqrt(sx * sx + sy * sy + sz * sz) / 2 <= size / 2.0;
            case BOTTLE -> sx <= size && Math.sqrt(sy * sy + sz * sz) / 2 <= bottleRadius(size);
            case JAR -> sy <= size && Math.sqrt(sx * sx + sz * sz) / 2 <= jarRadius(size);
        };
    }
}
