package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * The globe dimension: an empty world where every globe gets its own walled-in build space.
 * Globe n's space sits at x = n * SPACING, so the globe a player is in follows from their position.
 */
public final class GlobeSpace
{
    public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(SnowGlobeMod.MODID, "globe"));
    /** Inside size of a globe, in blocks, along each axis. */
    public static final int SIZE = 16;
    private static final int SPACING = 64;
    private static final int BASE_Y = 64;
    private static final String RETURN_KEY = SnowGlobeMod.MODID + ":return";

    private GlobeSpace() {}

    /** Lowest corner of globe {@code id}'s build space (the walls sit just outside it). */
    public static BlockPos interiorMin(int id)
    {
        return new BlockPos(id * SPACING + 1, BASE_Y + 1, 1);
    }

    public static int idAt(BlockPos pos)
    {
        return Math.floorDiv(pos.getX(), SPACING);
    }

    /** True for cells outside the build space that touch it, even diagonally: together they seal it in. */
    private static boolean shell(GlobeKind kind, int x, int y, int z)
    {
        if (kind.inside(x, y, z))
            return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (kind.inside(x + dx, y + dy, z + dz))
                        return true;
        return false;
    }

    /** Builds a new globe's glass walls and starter scene, and returns its id. */
    public static int create(MinecraftServer server, GlobeKind kind)
    {
        GlobeSavedData data = GlobeSavedData.get(server);
        int id = data.allocateId(kind);
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return id;

        BlockPos min = interiorMin(id);
        BlockState wall = SnowGlobeMod.GLOBE_WALL.get().defaultBlockState();
        for (int x = -1; x <= SIZE; x++)
            for (int y = -1; y <= SIZE; y++)
                for (int z = -1; z <= SIZE; z++)
                    if (shell(kind, x, y, z))
                        level.setBlock(min.offset(x, y, z), wall, Block.UPDATE_CLIENTS);
        GlobeScenes.build(level, min, kind);

        data.storeSnapshot(id, GlobeSnapshot.capture(level, min, kind));
        return id;
    }

    public static void enter(ServerPlayer player, GlobeBlockEntity globe)
    {
        if (player.level().dimension() == LEVEL)
        {
            player.displayClientMessage(Component.translatable("snowglobe.message.nested"), true);
            return;
        }
        ServerLevel level = player.server.getLevel(LEVEL);
        if (level == null)
            return;
        int id = globe.ensureGlobe(player.server);

        CompoundTag back = new CompoundTag();
        back.putString("Dim", player.level().dimension().location().toString());
        back.putDouble("X", player.getX());
        back.putDouble("Y", player.getY());
        back.putDouble("Z", player.getZ());
        back.putFloat("Yaw", player.getYRot());
        back.putFloat("Pitch", player.getXRot());
        player.getPersistentData().put(RETURN_KEY, back);

        BlockPos spawn = findSpawn(level, id);
        player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.displayClientMessage(Component.translatable("snowglobe.message.entered"), false);
    }

    public static void leave(ServerPlayer player)
    {
        MinecraftServer server = player.server;
        if (player.level().dimension() == LEVEL)
            refreshSnapshot(server, idAt(player.blockPosition()));

        CompoundTag back = player.getPersistentData().getCompound(RETURN_KEY);
        player.getPersistentData().remove(RETURN_KEY);
        ResourceLocation dim = ResourceLocation.tryParse(back.getString("Dim"));
        ServerLevel target = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (target == null || target.dimension() == LEVEL)
        {
            // No way back recorded (e.g. after a death inside): go to world spawn.
            target = server.overworld();
            BlockPos spawn = target.getSharedSpawnPos();
            player.teleportTo(target, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
            return;
        }
        player.teleportTo(target, back.getDouble("X"), back.getDouble("Y"), back.getDouble("Z"), back.getFloat("Yaw"), back.getFloat("Pitch"));
    }

    /** Finds a free spot to stand, starting from the middle of the globe and working outwards. */
    private static BlockPos findSpawn(ServerLevel level, int id)
    {
        BlockPos min = interiorMin(id);
        for (int ring = 0; ring < SIZE / 2; ring++)
            for (int dx = -ring; dx <= ring; dx++)
                for (int dz = -ring; dz <= ring; dz++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring)
                        continue;
                    BlockPos spot = standingSpot(level, min.offset(SIZE / 2 + dx, 0, SIZE / 2 + dz));
                    if (spot != null)
                        return spot;
                }
        return min.offset(SIZE / 2, SIZE / 2, SIZE / 2);
    }

    /** The lowest spot in this column with ground below and two free blocks above, if there is one. */
    private static BlockPos standingSpot(ServerLevel level, BlockPos bottom)
    {
        for (int y = 1; y < SIZE - 1; y++)
        {
            BlockPos feet = bottom.above(y);
            if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                    && level.getBlockState(feet.above()).getBlock() != SnowGlobeMod.GLOBE_WALL.get())
                return feet;
        }
        return null;
    }

    private static boolean solid(ServerLevel level, BlockPos pos)
    {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static void refreshSnapshot(MinecraftServer server, int id)
    {
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null || id < 0)
            return;
        GlobeSavedData data = GlobeSavedData.get(server);
        data.storeSnapshot(id, GlobeSnapshot.capture(level, interiorMin(id), data.kindOf(id)));
    }

    /** Once a second, re-captures every globe someone is building in, so the miniature follows along live. */
    static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0)
            return;
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null || level.players().isEmpty())
            return;
        Set<Integer> occupied = new HashSet<>();
        for (ServerPlayer player : level.players())
            occupied.add(idAt(player.blockPosition()));
        for (int id : occupied)
            refreshSnapshot(server, id);
    }
}
