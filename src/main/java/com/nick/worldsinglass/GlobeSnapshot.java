package com.nick.worldsinglass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable copy of the blocks inside one globe, small enough to send to clients. Blocks with block
 * entities keep their data too, so blocks drawn from it (Create's copycats, cogwheels, ...) look right.
 */
public final class GlobeSnapshot
{
    /** The most cells a snapshot has along each axis. Bigger globes are shown downsampled to fit. */
    public static final int MAX_GRID = 64;

    /** Cells along each axis. */
    private final int size;
    /** Blocks of the globe per cell along each axis: 1 normally, more for globes bigger than {@link #MAX_GRID}. */
    private final int scale;
    private final List<BlockState> palette;
    private final int[] cells;
    /** Block entity data by cell index, for the cells that have one. */
    private final Map<Integer, CompoundTag> blockEntities;
    private List<BlockPos> visible;
    private List<BlockPos> fluids;
    private double contentRadius = -1;
    private MiniatureLayout cachedLayout;
    /** Client: whatever the renderer builds from this snapshot (its block entities). Not touched on the server. */
    public Object clientCache;

    public MiniatureLayout layout(GlobeKind kind)
    {
        if (cachedLayout == null)
            cachedLayout = MiniatureLayout.compute(kind, this);
        return cachedLayout;
    }

    private GlobeSnapshot(int size, int scale, List<BlockState> palette, int[] cells, Map<Integer, CompoundTag> blockEntities)
    {
        this.size = size;
        this.scale = scale;
        this.palette = palette;
        this.cells = cells;
        this.blockEntities = blockEntities;
    }

    /**
     * Captures a globe of {@code globeSize} blocks. Up to {@link #MAX_GRID} that's block for block; bigger
     * globes are downsampled: each cell stands for a cube of blocks and shows the most common block in it.
     */
    public static GlobeSnapshot capture(ServerLevel level, BlockPos min, GlobeKind kind, int globeSize)
    {
        int scale = (globeSize + MAX_GRID - 1) / MAX_GRID;
        int size = (globeSize + scale - 1) / scale;
        BlockState[] chosen = new BlockState[size * size * size];
        int[] votes = new int[chosen.length];
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        GlobeSpace.forEachBlock(level, min, globeSize, (x, y, z, state, pos) -> {
            // Only the build space belongs in the miniature (not the walls in a dome's corners).
            if (!kind.inside(x, y, z, globeSize))
                return;
            int i = ((y / scale) * size + z / scale) * size + x / scale;
            // Majority vote: keeps the most common block of each cube without counting them all.
            if (votes[i] == 0)
            {
                chosen[i] = state;
                votes[i] = 1;
            }
            else
                votes[i] += chosen[i] == state ? 1 : -1;
            if (scale == 1 && state.hasBlockEntity())
            {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null)
                {
                    CompoundTag data = be.saveWithFullMetadata(level.registryAccess());
                    // Mods' extra data (energy, fluids, ...) never changes a block's looks, and data from
                    // mods that aren't installed would only fill the log with warnings on every client.
                    data.remove("neoforge:attachments");
                    // A globe standing in this one keeps its own miniature, but not the globes inside that: each
                    // level would carry all the ones below it.
                    if (data.contains("Snapshot", Tag.TAG_COMPOUND))
                        data.getCompound("Snapshot").remove("BlockEntities");
                    blockEntities.put(i, data);
                }
            }
        });

        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] cells = new int[chosen.length];
        for (int i = 0; i < chosen.length; i++)
        {
            BlockState state = chosen[i] != null ? chosen[i] : Blocks.AIR.defaultBlockState();
            cells[i] = index.computeIfAbsent(state, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
        }
        return new GlobeSnapshot(size, scale, palette, cells, blockEntities);
    }

    /** Cells along each axis (the globe's size, divided by {@link #scale()}). */
    public int size()
    {
        return size;
    }

    /** Blocks of the globe per cell along each axis. */
    public int scale()
    {
        return scale;
    }

    public BlockState get(int x, int y, int z)
    {
        return palette.get(cells[(y * size + z) * size + x]);
    }

    /** The block entity data saved for this cell, or null. */
    @Nullable
    public CompoundTag blockEntity(int x, int y, int z)
    {
        return blockEntities.get((y * size + z) * size + x);
    }

    /** Blocks worth drawing in the miniature: not air, and not buried on all six sides. */
    public List<BlockPos> visibleBlocks()
    {
        if (visible == null)
        {
            List<BlockPos> list = new ArrayList<>();
            for (int y = 0; y < size; y++)
                for (int z = 0; z < size; z++)
                    for (int x = 0; x < size; x++)
                    {
                        BlockState state = get(x, y, z);
                        boolean drawn = state.getRenderShape() == RenderShape.MODEL || blockEntity(x, y, z) != null;
                        if (drawn && !state.isAir() && !buried(x, y, z))
                            list.add(new BlockPos(x, y, z));
                    }
            visible = list;
        }
        return visible;
    }

    /**
     * How far the contents reach from the centre, in blocks (to the far corner of the farthest block).
     * Lets a round globe zoom in on a build that doesn't fill it.
     */
    public double contentRadius()
    {
        if (contentRadius < 0)
        {
            double c = size / 2.0, max = 0;
            for (int y = 0; y < size; y++)
                for (int z = 0; z < size; z++)
                    for (int x = 0; x < size; x++)
                        if (!get(x, y, z).isAir())
                        {
                            double dx = Math.abs(x + 0.5 - c) + 0.5, dy = Math.abs(y + 0.5 - c) + 0.5, dz = Math.abs(z + 0.5 - c) + 0.5;
                            max = Math.max(max, dx * dx + dy * dy + dz * dz);
                        }
            contentRadius = Math.sqrt(max);
        }
        return contentRadius;
    }

    /** Water and lava worth drawing: fluid cells with at least one side not touching the same fluid. */
    public List<BlockPos> fluidCells()
    {
        if (fluids == null)
        {
            List<BlockPos> list = new ArrayList<>();
            for (int y = 0; y < size; y++)
                for (int z = 0; z < size; z++)
                    for (int x = 0; x < size; x++)
                    {
                        FluidState fluid = get(x, y, z).getFluidState();
                        if (!fluid.isEmpty() && (!sameFluid(fluid, x - 1, y, z) || !sameFluid(fluid, x + 1, y, z)
                                || !sameFluid(fluid, x, y - 1, z) || !sameFluid(fluid, x, y + 1, z)
                                || !sameFluid(fluid, x, y, z - 1) || !sameFluid(fluid, x, y, z + 1)))
                            list.add(new BlockPos(x, y, z));
                    }
            fluids = list;
        }
        return fluids;
    }

    private boolean inBounds(int x, int y, int z)
    {
        return x >= 0 && y >= 0 && z >= 0 && x < size && y < size && z < size;
    }

    private boolean sameFluid(FluidState fluid, int x, int y, int z)
    {
        return inBounds(x, y, z) && get(x, y, z).getFluidState().getType().isSame(fluid.getType());
    }

    private boolean buried(int x, int y, int z)
    {
        return solid(x - 1, y, z) && solid(x + 1, y, z) && solid(x, y - 1, z)
                && solid(x, y + 1, z) && solid(x, y, z - 1) && solid(x, y, z + 1);
    }

    private boolean solid(int x, int y, int z)
    {
        return inBounds(x, y, z) && get(x, y, z).isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    public CompoundTag toTag()
    {
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette)
            paletteTag.add(NbtUtils.writeBlockState(state));
        CompoundTag tag = new CompoundTag();
        tag.putInt("Size", size);
        tag.putInt("Scale", scale);
        tag.put("Palette", paletteTag);
        // Most globes use under 256 kinds of block: one byte per cell then, a quarter of the size.
        if (palette.size() <= 256)
        {
            byte[] bytes = new byte[cells.length];
            for (int i = 0; i < cells.length; i++)
                bytes[i] = (byte) cells[i];
            tag.putByteArray("Bytes", bytes);
        }
        else
            tag.putIntArray("Cells", cells);
        if (!blockEntities.isEmpty())
        {
            ListTag list = new ListTag();
            blockEntities.forEach((cell, data) -> {
                CompoundTag entry = data.copy();
                entry.putInt("worldsinglass:cell", cell);
                list.add(entry);
            });
            tag.put("BlockEntities", list);
        }
        return tag;
    }

    @Nullable
    public static GlobeSnapshot fromTag(CompoundTag tag)
    {
        int size = tag.contains("Size") ? tag.getInt("Size") : GlobeSpace.DEFAULT_SIZE;
        int scale = Math.max(1, tag.getInt("Scale"));
        if (size <= 0 || size > GlobeSpace.MAX_SIZE)
            return null;
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
        int[] cells;
        if (tag.contains("Bytes"))
        {
            byte[] bytes = tag.getByteArray("Bytes");
            cells = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++)
                cells[i] = bytes[i] & 0xFF;
        }
        else
            cells = tag.getIntArray("Cells");
        if (paletteTag.isEmpty() || cells.length != size * size * size)
            return null;
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++)
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i)));
        for (int i = 0; i < cells.length; i++)
            if (cells[i] < 0 || cells[i] >= palette.size())
            {
                palette.add(Blocks.AIR.defaultBlockState());
                cells[i] = palette.size() - 1;
            }
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        ListTag list = tag.getList("BlockEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag entry = list.getCompound(i).copy();
            int cell = entry.getInt("worldsinglass:cell");
            entry.remove("worldsinglass:cell");
            if (cell >= 0 && cell < cells.length)
                blockEntities.put(cell, entry);
        }
        return new GlobeSnapshot(size, scale, palette, cells, blockEntities);
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof GlobeSnapshot other && size == other.size && scale == other.scale && palette.equals(other.palette)
                && Arrays.equals(cells, other.cells) && blockEntities.equals(other.blockEntities);
    }

    @Override
    public int hashCode()
    {
        return 31 * (31 * palette.hashCode() + Arrays.hashCode(cells)) + blockEntities.hashCode();
    }
}
