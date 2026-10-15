package com.nick.snowglobe;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The kinds of globe: a round snow globe on a pedestal, or a square glass terrarium. */
public enum GlobeKind
{
    SNOW_GLOBE("snow_globe", true, Shapes.or(
            Block.box(4, 0, 4, 12, 2.75, 12),          // pedestal
            Block.box(3.5, 2, 3.5, 12.5, 11, 12.5))),  // glass ball
    TERRARIUM("terrarium", false,
            Block.box(2, 0, 2, 14, 12, 14));

    private final String name;
    /** Round globes have a dome-shaped build space; others use the whole cube. */
    public final boolean round;
    public final VoxelShape shape;

    GlobeKind(String name, boolean round, VoxelShape shape)
    {
        this.name = name;
        this.round = round;
        this.shape = shape;
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

    /** True for the cells of the SIZE³ cube that belong to this kind's build space. */
    public boolean inside(int x, int y, int z)
    {
        int size = GlobeSpace.SIZE;
        if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size)
            return false;
        if (!round)
            return true;
        double r = size / 2.0;
        double dx = x + 0.5 - r, dy = y + 0.5 - r, dz = z + 0.5 - r;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }
}
