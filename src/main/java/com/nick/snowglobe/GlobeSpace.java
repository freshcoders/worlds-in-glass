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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
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
                    && level.getFluidState(feet).isEmpty()
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

    // ---- Live globes: while a globe block is loaded near a player, its space keeps running so its mobs move ----

    /** Keeps a globe's chunks loaded and ticking. Added and removed by hand, never saved with the world. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create(SnowGlobeMod.MODID + ":globe", Comparator.comparingLong(ChunkPos::toLong));
    /** How often mob positions are captured and sent, in ticks. Clients smooth the movement in between. */
    public static final int ENTITY_INTERVAL = 4;
    private static final int ACTIVE_TICKS = 40;

    /** The mobs in one globe at one moment, plus a counter that goes up whenever they moved. */
    public record EntityFrame(ListTag entities, long version) {}

    private static final Map<Integer, Integer> lastSeen = new HashMap<>();
    private static final Set<Integer> ticketed = new HashSet<>();
    private static final Map<Integer, EntityFrame> frames = new HashMap<>();

    /** Called every tick by each loaded globe block: its space should keep running for now. */
    static void markSeen(MinecraftServer server, int id)
    {
        lastSeen.put(id, server.getTickCount());
    }

    @Nullable
    static EntityFrame entityFrame(int id)
    {
        return frames.get(id);
    }

    /** Positions of the living things in a globe (not players), relative to the build space. */
    private static void captureEntities(ServerLevel level, int id)
    {
        BlockPos min = interiorMin(id);
        AABB box = new AABB(min, min.offset(SIZE, SIZE, SIZE));
        ListTag list = new ListTag();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !(e instanceof Player)))
        {
            ResourceLocation type = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (type == null)
                continue;
            CompoundTag tag = new CompoundTag();
            tag.putString("T", type.toString());
            tag.putInt("I", entity.getId());
            tag.putFloat("X", (float) (entity.getX() - min.getX()));
            tag.putFloat("Y", (float) (entity.getY() - min.getY()));
            tag.putFloat("Z", (float) (entity.getZ() - min.getZ()));
            tag.putFloat("B", entity.yBodyRot);
            tag.putFloat("H", entity.yHeadRot);
            if (entity.isInWater())
                tag.putBoolean("W", true);
            CompoundTag looks = looks(entity);
            if (!looks.isEmpty())
                tag.put("L", looks);
            list.add(tag);
        }
        EntityFrame old = frames.get(id);
        if (old == null || !old.entities().equals(list))
            frames.put(id, new EntityFrame(list, old == null ? 1 : old.version() + 1));
    }

    /** Saved-data keys that change how a mob looks (variants, colours, baby or not), for the client's copy. */
    private static final Set<String> LOOK_KEYS = Set.of(
            "Type", "variant", "Variant", "RabbitType", "Pumpkin", "Color", "CollarColor", "Sheared",
            "Saddle", "IsBaby", "Age", "CustomName", "ArmorItems", "HandItems");

    private static CompoundTag looks(LivingEntity entity)
    {
        CompoundTag all = entity.saveWithoutId(new CompoundTag());
        CompoundTag looks = new CompoundTag();
        for (String key : LOOK_KEYS)
        {
            Tag value = all.get(key);
            // Age counts up while growing; only whether it's a baby matters for looks.
            if (key.equals("Age") && value != null)
                looks.putInt(key, all.getInt(key) < 0 ? -24000 : 0);
            else if (value != null)
                looks.put(key, value);
        }
        return looks;
    }

    private static void setTickets(ServerLevel level, int id, boolean add)
    {
        BlockPos min = interiorMin(id);
        for (int cx = min.getX() >> 4; cx <= (min.getX() + SIZE - 1) >> 4; cx++)
            for (int cz = min.getZ() >> 4; cz <= (min.getZ() + SIZE - 1) >> 4; cz++)
            {
                ChunkPos chunk = new ChunkPos(cx, cz);
                // Distance 2 gives ticket level 31: the chunk's entities tick.
                if (add)
                    level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk);
                else
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, chunk);
            }
    }

    /**
     * Runs the live globes: keeps their chunks ticking, captures their mobs a few times a second
     * and their blocks once a second, so the miniatures follow along.
     */
    static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return;
        int now = server.getTickCount();

        Set<Integer> active = new HashSet<>();
        lastSeen.entrySet().removeIf(e -> now - e.getValue() > ACTIVE_TICKS);
        active.addAll(lastSeen.keySet());
        for (ServerPlayer player : level.players())
            active.add(idAt(player.blockPosition()));

        for (int id : active)
            if (ticketed.add(id))
                setTickets(level, id, true);
        for (Iterator<Integer> it = ticketed.iterator(); it.hasNext(); )
        {
            int id = it.next();
            if (!active.contains(id))
            {
                setTickets(level, id, false);
                frames.remove(id);
                it.remove();
            }
        }
        if (active.isEmpty())
            return;

        // Without players or forced chunks a level stops ticking its entities after a while; keep it awake.
        level.resetEmptyTime();
        if (now % ENTITY_INTERVAL == 0)
            for (int id : active)
                captureEntities(level, id);
        if (now % 20 == 0)
            for (int id : active)
                refreshSnapshot(server, id);
    }

    /** Forget live globes when a world closes, so they don't leak into the next one opened. */
    static void onServerStopped(ServerStoppedEvent event)
    {
        lastSeen.clear();
        ticketed.clear();
        frames.clear();
    }
}
