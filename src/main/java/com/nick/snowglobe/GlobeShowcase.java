package com.nick.snowglobe;

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
import net.minecraft.world.level.block.LanternBlock;

/**
 * Dev only: builds a display plaza in front of you for screenshots. Every kind of globe on its own pedestal in a
 * ring (each with its starter scene), a big snow globe with a music box in the middle, and two collector's shelves
 * at the back, all on a stone floor with lanterns.
 */
final class GlobeShowcase
{
    private static final int RADIUS = 10;
    private static final int RING = 6;

    private GlobeShowcase() {}

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
        BlockPos center = player.blockPosition().relative(look, RADIUS + 2);
        int floorY = player.blockPosition().getY() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Clear the space and lay the floor: smooth stone, a ring of polished diorite, a spruce border.
        for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++)
            for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++)
            {
                double d = Math.hypot(dx, dz);
                if (d > RADIUS + 0.5)
                    continue;
                for (int y = floorY + 1; y <= floorY + 12; y++)
                    level.setBlock(pos.set(center.getX() + dx, y, center.getZ() + dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = floorY - 3; y < floorY; y++)
                    if (level.getBlockState(pos.set(center.getX() + dx, y, center.getZ() + dz)).canBeReplaced())
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                Block floor = d > RADIUS - 0.5 ? Blocks.SPRUCE_PLANKS
                        : Math.abs(d - RING) < 0.8 ? Blocks.POLISHED_DIORITE
                        : d < 2.5 ? Blocks.POLISHED_DEEPSLATE
                        : (dx + dz) % 2 == 0 ? Blocks.SMOOTH_STONE : Blocks.POLISHED_ANDESITE;
                level.setBlock(pos.set(center.getX() + dx, floorY, center.getZ() + dz), floor.defaultBlockState(), Block.UPDATE_CLIENTS);
            }

        // Lanterns on posts around the edge.
        for (int i = 0; i < 8; i++)
        {
            double a = Math.PI * 2 * (i + 0.5) / 8;
            int x = center.getX() + (int) Math.round(Math.cos(a) * (RADIUS - 1.5)), z = center.getZ() + (int) Math.round(Math.sin(a) * (RADIUS - 1.5));
            level.setBlock(pos.set(x, floorY + 1, z), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.set(x, floorY + 2, z), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.set(x, floorY + 3, z), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false), Block.UPDATE_ALL);
        }

        // Every kind on a pedestal, in a ring, side-on to someone standing in the middle.
        GlobeKind[] kinds = GlobeKind.values();
        for (int i = 0; i < kinds.length; i++)
        {
            double a = Math.PI * 2 * i / kinds.length;
            int x = center.getX() + (int) Math.round(Math.cos(a) * RING), z = center.getZ() + (int) Math.round(Math.sin(a) * RING);
            BlockPos at = new BlockPos(x, floorY + 1, z);
            // A two-block pedestal puts each globe at eye height.
            level.setBlock(at, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at.above(), Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState(), Block.UPDATE_ALL);
            Direction toCenter = Direction.getNearest(center.getX() - x, 0, center.getZ() - z);
            placeGlobe(server, level, at.above(2), kinds[i], toCenter.getOpposite().getClockWise(), 0, false);
        }

        // The centrepiece: a big snow globe, with a music box to wind.
        placeGlobe(server, level, center.atY(floorY + 1), GlobeKind.SNOW_GLOBE, look.getClockWise(), 48, true);

        // Two shelves at the back, raised on a plinth, with more globes on them.
        Direction side = look.getClockWise();
        BlockPos back = center.relative(look, RADIUS - 2).atY(floorY + 1);
        GlobeKind[][] shelves = {{GlobeKind.TERRARIUM, GlobeKind.STAR_GLOBE}, {GlobeKind.AQUARIUM, GlobeKind.DESERT_GLOBE}};
        for (int s = 0; s < 2; s++)
        {
            BlockPos at = back.relative(side, s == 0 ? -1 : 1);
            level.setBlock(at, Blocks.SPRUCE_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at.above(), SnowGlobeMod.SHELF.get().defaultBlockState().setValue(GlobeShelfBlock.FACING, look.getOpposite()), Block.UPDATE_ALL);
            if (level.getBlockEntity(at.above()) instanceof GlobeShelfBlockEntity shelf)
                for (int slot = 0; slot < 2; slot++)
                    shelf.set(slot, globeItem(server, shelves[s][slot]));
        }

        player.sendSystemMessage(Component.literal("Built a globe showcase " + (RADIUS + 2) + " blocks " + look.getName() + " of you."));
        return 1;
    }

    /** Places a globe with its starter scene; {@code size} above 16 makes it bigger (and drawn bigger). */
    private static void placeGlobe(MinecraftServer server, ServerLevel level, BlockPos at, GlobeKind kind, Direction facing, int size, boolean musicBox)
    {
        level.setBlock(at, SnowGlobeMod.globe(kind).get().defaultBlockState().setValue(GlobeBlock.FACING, facing), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(at) instanceof GlobeBlockEntity globe))
            return;
        int id = globe.ensureGlobe(server, true);
        if (size > GlobeSpace.DEFAULT_SIZE)
            GlobeSpace.resize(server, id, size);
        if (musicBox)
            GlobeSavedData.get(server).setMusicBox(id, true);
        globe.updateDisplaySize();
        globe.refreshExtras();
        level.sendBlockUpdated(at, globe.getBlockState(), globe.getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** A globe item with a fresh starter scene, as if picked up after placing it. */
    private static ItemStack globeItem(MinecraftServer server, GlobeKind kind)
    {
        int id = GlobeSpace.create(server, kind, true);
        GlobeSavedData data = GlobeSavedData.get(server);
        ItemStack stack = new ItemStack(SnowGlobeMod.GLOBE_ITEMS.get(kind).get());
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
        stack.set(SnowGlobeMod.GLOBE_DATA.get(), CustomData.of(tag));
        return stack;
    }
}
