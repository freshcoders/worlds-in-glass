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
    SHIP_BOTTLE("ship_bottle", Space.BOTTLE, Block.box(1.5, 0, 3.5, 15.6, 9.3, 12.5));

    /** The shape of the build space inside the 16³ cube. */
    public enum Space
    {
        /** A ball touching the cube's sides. */
        SPHERE,
        /** The whole cube. */
        CUBE,
        /** A bottle lying along the x axis: a cylinder 11 blocks across. */
        BOTTLE
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

    /** Radius of the bottle's build space, in blocks. */
    public static final double BOTTLE_RADIUS = 5.5;

    /** True for the cells of the SIZE³ cube that belong to this kind's build space. */
    public boolean inside(int x, int y, int z)
    {
        int size = GlobeSpace.SIZE;
        if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size)
            return false;
        double c = size / 2.0;
        double dx = x + 0.5 - c, dy = y + 0.5 - c, dz = z + 0.5 - c;
        return switch (space)
        {
            case CUBE -> true;
            case SPHERE -> dx * dx + dy * dy + dz * dz <= c * c;
            case BOTTLE -> dy * dy + dz * dz <= BOTTLE_RADIUS * BOTTLE_RADIUS;
        };
    }
}
