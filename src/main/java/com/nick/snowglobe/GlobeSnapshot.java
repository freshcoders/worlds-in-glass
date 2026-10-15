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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An immutable copy of the blocks inside one globe, small enough to send to clients. */
public final class GlobeSnapshot
{
    private static final int SIZE = GlobeSpace.SIZE;

    private final List<BlockState> palette;
    private final int[] cells;
    private List<BlockPos> visible;
    private List<BlockPos> fluids;

    private GlobeSnapshot(List<BlockState> palette, int[] cells)
    {
        this.palette = palette;
        this.cells = cells;
    }

    public static GlobeSnapshot capture(Level level, BlockPos min, GlobeKind kind)
    {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] cells = new int[SIZE * SIZE * SIZE];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = 0; y < SIZE; y++)
            for (int z = 0; z < SIZE; z++)
                for (int x = 0; x < SIZE; x++)
                {
                    // Only the build space belongs in the miniature (not the walls in a dome's corners).
                    BlockState state = kind.inside(x, y, z)
                            ? level.getBlockState(pos.set(min.getX() + x, min.getY() + y, min.getZ() + z))
                            : Blocks.AIR.defaultBlockState();
                    cells[i++] = index.computeIfAbsent(state, s -> {
                        palette.add(s);
                        return palette.size() - 1;
                    });
                }
        return new GlobeSnapshot(palette, cells);
    }

    public BlockState get(int x, int y, int z)
    {
        return palette.get(cells[(y * SIZE + z) * SIZE + x]);
    }

    /** Blocks worth drawing in the miniature: not air, and not buried on all six sides. */
    public List<BlockPos> visibleBlocks()
    {
        if (visible == null)
        {
            List<BlockPos> list = new ArrayList<>();
            for (int y = 0; y < SIZE; y++)
                for (int z = 0; z < SIZE; z++)
                    for (int x = 0; x < SIZE; x++)
                        if (get(x, y, z).getRenderShape() == RenderShape.MODEL && !buried(x, y, z))
                            list.add(new BlockPos(x, y, z));
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
            for (int y = 0; y < SIZE; y++)
                for (int z = 0; z < SIZE; z++)
                    for (int x = 0; x < SIZE; x++)
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

    private boolean sameFluid(FluidState fluid, int x, int y, int z)
    {
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE)
            return false;
        return get(x, y, z).getFluidState().getType().isSame(fluid.getType());
    }

    private boolean buried(int x, int y, int z)
    {
        return solid(x - 1, y, z) && solid(x + 1, y, z) && solid(x, y - 1, z)
                && solid(x, y + 1, z) && solid(x, y, z - 1) && solid(x, y, z + 1);
    }

    private boolean solid(int x, int y, int z)
    {
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE)
            return false;
        return get(x, y, z).isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    public CompoundTag toTag()
    {
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette)
            paletteTag.add(NbtUtils.writeBlockState(state));
        CompoundTag tag = new CompoundTag();
        tag.put("Palette", paletteTag);
        tag.putIntArray("Cells", cells);
        return tag;
    }

    @Nullable
    public static GlobeSnapshot fromTag(CompoundTag tag)
    {
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
        int[] cells = tag.getIntArray("Cells");
        if (paletteTag.isEmpty() || cells.length != SIZE * SIZE * SIZE)
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
        return new GlobeSnapshot(palette, cells);
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof GlobeSnapshot other && palette.equals(other.palette) && Arrays.equals(cells, other.cells);
    }

    @Override
    public int hashCode()
    {
        return 31 * palette.hashCode() + Arrays.hashCode(cells);
    }
}
