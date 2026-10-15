package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
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
    private final int size;
    private final List<BlockState> palette;
    private final int[] cells;
    /** Block entity data by cell index, for the cells that have one. */
    private final Map<Integer, CompoundTag> blockEntities;
    private List<BlockPos> visible;
    private List<BlockPos> fluids;
    /** Client: whatever the renderer builds from this snapshot (its block entities). Not touched on the server. */
    public Object clientCache;

    private GlobeSnapshot(int size, List<BlockState> palette, int[] cells, Map<Integer, CompoundTag> blockEntities)
    {
        this.size = size;
        this.palette = palette;
        this.cells = cells;
        this.blockEntities = blockEntities;
    }

    public static GlobeSnapshot capture(Level level, BlockPos min, GlobeKind kind, int size)
    {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        int[] cells = new int[size * size * size];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = 0; y < size; y++)
            for (int z = 0; z < size; z++)
                for (int x = 0; x < size; x++)
                {
                    // Only the build space belongs in the miniature (not the walls in a dome's corners).
                    BlockState state = Blocks.AIR.defaultBlockState();
                    if (kind.inside(x, y, z, size))
                    {
                        pos.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                        state = level.getBlockState(pos);
                        BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
                        if (be != null)
                            blockEntities.put(i, be.saveWithFullMetadata(level.registryAccess()));
                    }
                    cells[i++] = index.computeIfAbsent(state, s -> {
                        palette.add(s);
                        return palette.size() - 1;
                    });
                }
        return new GlobeSnapshot(size, palette, cells, blockEntities);
    }

    public int size()
    {
        return size;
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
                entry.putInt("snowglobe:cell", cell);
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
            int cell = entry.getInt("snowglobe:cell");
            entry.remove("snowglobe:cell");
            if (cell >= 0 && cell < cells.length)
                blockEntities.put(cell, entry);
        }
        return new GlobeSnapshot(size, palette, cells, blockEntities);
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof GlobeSnapshot other && size == other.size && palette.equals(other.palette)
                && Arrays.equals(cells, other.cells) && blockEntities.equals(other.blockEntities);
    }

    @Override
    public int hashCode()
    {
        return 31 * (31 * palette.hashCode() + Arrays.hashCode(cells)) + blockEntities.hashCode();
    }
}
