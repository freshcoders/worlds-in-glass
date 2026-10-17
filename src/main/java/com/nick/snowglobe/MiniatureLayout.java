package com.nick.snowglobe;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where a globe's miniature sits inside its block, and how small it's drawn. Shared by the renderer (outside,
 * drawing the miniature) and the server (inside, placing the giant outside world at the matching scale), so the
 * two line up exactly.
 *
 * @param cell   size of one snapshot cell, in blocks of the (small, unturned) globe block
 * @param center where the snapshot's {@code pivot} cell corner lands inside the globe block
 * @param pivot  the snapshot grid point placed at {@code center}, in cells
 * @param scale  blocks of build space per snapshot cell (1, or more for downsampled big globes)
 */
public record MiniatureLayout(float cell, Vec3 center, Vec3 pivot, int scale)
{
    // Ball (snow and storm globes), in block units: resting on top of the pedestal's rim (2.75 px), not sunk into
    // it, so the pedestal never reaches into the build space when seen giant from inside.
    public static final float BALL_RADIUS = 4.5f / 16f;
    public static final float BALL_CENTER_Y = 7.25f / 16f;
    // Terrarium: the miniature fills the glass case above the wooden base.
    public static final float CASE_WIDTH = 10f / 16f;
    public static final float CASE_FLOOR = 1f / 16f;
    // Bottle, lying along x on its stand.
    public static final float BOTTLE_Y = 5.25f / 16f;
    public static final float BOTTLE_MID_X = 6.75f / 16f;
    public static final float BOTTLE_CELL = 0.6f / 16f;
    // Jar, standing upright on its base: the build space's cylinder just inside the glass.
    public static final float JAR_RADIUS = 4.75f / 16f;
    public static final float JAR_INNER = 4.4f / 16f;
    public static final float JAR_FLOOR = 1f / 16f;
    public static final float JAR_TOP = 12.5f / 16f;

    private static final java.util.Map<GlobeKind, MiniatureLayout> DEFAULT_LAYOUTS = new java.util.EnumMap<>(GlobeKind.class);
    static
    {
        for (GlobeKind k : GlobeKind.values())
            DEFAULT_LAYOUTS.put(k, compute(k, null));
    }

    public static MiniatureLayout of(GlobeKind kind, @Nullable GlobeSnapshot snapshot)
    {
        return snapshot != null ? snapshot.layout(kind) : DEFAULT_LAYOUTS.get(kind);
    }

    public static MiniatureLayout compute(GlobeKind kind, @Nullable GlobeSnapshot snapshot)
    {
        int size = snapshot != null ? snapshot.size() : GlobeSpace.DEFAULT_SIZE;
        int scale = snapshot != null ? snapshot.scale() : 1;
        float half = size / 2f;
        return switch (kind)
        {
            case SNOW_GLOBE, STORM_GLOBE, DESERT_GLOBE, STAR_GLOBE ->
            {
                // Fit what's inside just within the ball: a full dome reaches about 1.11 half-sizes from its centre,
                // a smaller build less, so it's shown bigger. (Never zoom in further than an 8-block reach.)
                double reach = snapshot != null ? Math.min(snapshot.contentRadius(), half * 1.1125) : half * 1.1125;
                float cell = BALL_RADIUS * 0.94f / (float) Math.max(reach, 8);
                yield new MiniatureLayout(cell, new Vec3(0.5, BALL_CENTER_Y, 0.5), new Vec3(half, half, half), scale);
            }
            case SHIP_BOTTLE -> new MiniatureLayout(BOTTLE_CELL * GlobeSpace.DEFAULT_SIZE / size,
                    new Vec3(BOTTLE_MID_X, BOTTLE_Y, 0.5), new Vec3(half, half, half), scale);
            case TERRARIUM, AQUARIUM -> new MiniatureLayout(CASE_WIDTH / size, new Vec3(0.5, CASE_FLOOR + 0.002, 0.5), new Vec3(half, 0, half), scale);
            case NETHER_JAR -> new MiniatureLayout(JAR_INNER / (float) GlobeKind.jarRadius(size), new Vec3(0.5, JAR_FLOOR + 0.002, 0.5),
                    new Vec3(half, 0, half), scale);
        };
    }

    /** How many blocks of the build space one block of the outside world spans, for a globe drawn {@code display} times bigger. */
    public double worldToSpace(int display)
    {
        return scale / (cell * display);
    }
}
