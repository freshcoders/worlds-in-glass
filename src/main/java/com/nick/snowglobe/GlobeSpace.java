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
import net.minecraft.world.level.block.Blocks;
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

    /** Lowest corner of globe {@code id}'s build space (the walls sit one block outside it). */
    public static BlockPos interiorMin(int id)
    {
        return new BlockPos(id * SPACING + 1, BASE_Y + 1, 1);
    }

    public static int idAt(BlockPos pos)
    {
        return Math.floorDiv(pos.getX(), SPACING);
    }

    /** Builds a new globe's walls and grass floor, and returns its id. */
    public static int create(MinecraftServer server)
    {
        GlobeSavedData data = GlobeSavedData.get(server);
        int id = data.allocateId();
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return id;

        BlockPos min = interiorMin(id);
        BlockState wall = SnowGlobeMod.GLOBE_WALL.get().defaultBlockState();
        BlockState floor = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = -1; x <= SIZE; x++)
            for (int y = -1; y <= SIZE; y++)
                for (int z = -1; z <= SIZE; z++)
                {
                    boolean shell = x < 0 || y < 0 || z < 0 || x == SIZE || y == SIZE || z == SIZE;
                    if (shell)
                        level.setBlock(min.offset(x, y, z), wall, Block.UPDATE_CLIENTS);
                    else if (y == 0)
                        level.setBlock(min.offset(x, y, z), floor, Block.UPDATE_CLIENTS);
                }

        data.storeSnapshot(id, GlobeSnapshot.capture(level, min));
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

    /** Stands the player on top of whatever is built in the middle of the globe. */
    private static BlockPos findSpawn(ServerLevel level, int id)
    {
        BlockPos min = interiorMin(id);
        for (int y = SIZE - 2; y >= 0; y--)
        {
            BlockPos pos = min.offset(SIZE / 2, y, SIZE / 2);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty())
                return pos.above();
        }
        return min.offset(SIZE / 2, 0, SIZE / 2);
    }

    private static void refreshSnapshot(MinecraftServer server, int id)
    {
        ServerLevel level = server.getLevel(LEVEL);
        if (level != null && id >= 0)
            GlobeSavedData.get(server).storeSnapshot(id, GlobeSnapshot.capture(level, interiorMin(id)));
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
