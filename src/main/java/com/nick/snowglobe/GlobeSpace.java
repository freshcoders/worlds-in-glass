package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The globe dimension: an empty world where every globe gets its own walled-in build space.
 * Globe n's space sits at x = n * SPACING, so the globe a player is in follows from their position.
 */
public final class GlobeSpace
{
    public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(SnowGlobeMod.MODID, "globe"));
    /** Build space sizes (blocks along each axis). New globes start at the smallest; imports grow them to fit. */
    public static final List<Integer> SIZES = List.of(16, 24, 32, 48, 62);
    public static final int DEFAULT_SIZE = 16;
    /** The largest that fits between neighbouring globes (SPACING minus two walls). */
    public static final int MAX_SIZE = 62;
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
    private static boolean shell(GlobeKind kind, int size, int x, int y, int z)
    {
        if (kind.inside(x, y, z, size))
            return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (kind.inside(x + dx, y + dy, z + dz, size))
                        return true;
        return false;
    }

    private static void buildShell(ServerLevel level, BlockPos min, GlobeKind kind, int size, BlockState state)
    {
        for (int x = -1; x <= size; x++)
            for (int y = -1; y <= size; y++)
                for (int z = -1; z <= size; z++)
                    if (shell(kind, size, x, y, z))
                        level.setBlock(min.offset(x, y, z), state, Block.UPDATE_CLIENTS);
    }

    /** Builds a new globe's glass walls and starter scene, and returns its id. */
    public static int create(MinecraftServer server, GlobeKind kind)
    {
        GlobeSavedData data = GlobeSavedData.get(server);
        int id = data.allocateId(kind);
        data.setSize(id, DEFAULT_SIZE);
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return id;

        BlockPos min = interiorMin(id);
        buildShell(level, min, kind, DEFAULT_SIZE, SnowGlobeMod.GLOBE_WALL.get().defaultBlockState());
        GlobeScenes.build(level, min, kind);

        data.storeSnapshot(id, GlobeSnapshot.capture(level, min, kind, DEFAULT_SIZE));
        return id;
    }

    /**
     * Gives a globe a new size: new walls, and everything inside (blocks, their contents, mobs, players)
     * moved so it stays centred. Shrinking cuts off what no longer fits.
     */
    public static void resize(MinecraftServer server, int id, int newSize)
    {
        ServerLevel level = server.getLevel(LEVEL);
        GlobeSavedData data = GlobeSavedData.get(server);
        int oldSize = data.sizeOf(id);
        if (level == null || oldSize == newSize)
            return;
        GlobeKind kind = data.kindOf(id);
        BlockPos min = interiorMin(id);

        // Take everything out: blocks with their data, and the entities in the space.
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, CompoundTag> blockData = new HashMap<>();
        for (int x = 0; x < oldSize; x++)
            for (int y = 0; y < oldSize; y++)
                for (int z = 0; z < oldSize; z++)
                {
                    if (!kind.inside(x, y, z, oldSize))
                        continue;
                    BlockPos pos = min.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir())
                        continue;
                    states.put(new BlockPos(x, y, z), state);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null)
                        blockData.put(new BlockPos(x, y, z), be.saveWithoutMetadata(level.registryAccess()));
                }
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, new AABB(min.getCenter(), min.getCenter()).inflate(MAX_SIZE * 2), e -> idAt(e.blockPosition()) == id);
        clearSpace(level, min, kind, oldSize, false);
        buildShell(level, min, kind, oldSize, Blocks.AIR.defaultBlockState());

        // Put it back, shifted so it stays in the middle (a terrarium keeps its floor where it is).
        int shift = (newSize - oldSize) / 2;
        int shiftY = kind.space == GlobeKind.Space.CUBE ? 0 : shift;
        buildShell(level, min, kind, newSize, SnowGlobeMod.GLOBE_WALL.get().defaultBlockState());
        states.forEach((cell, state) -> {
            int x = cell.getX() + shift, y = cell.getY() + shiftY, z = cell.getZ() + shift;
            if (!kind.inside(x, y, z, newSize))
                return;
            BlockPos pos = min.offset(x, y, z);
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            CompoundTag tag = blockData.get(cell);
            BlockEntity be = tag == null ? null : level.getBlockEntity(pos);
            if (be != null)
                be.loadWithComponents(tag, level.registryAccess());
        });
        for (Entity entity : entities)
            entity.teleportTo(entity.getX() + shift, entity.getY() + shiftY, entity.getZ() + shift);

        data.setSize(id, newSize);
        data.storeSnapshot(id, GlobeSnapshot.capture(level, min, kind, newSize));
    }

    /** Empties a globe's build space: its blocks, and (unless asked to keep them) the mobs in it. */
    static void clearSpace(ServerLevel level, BlockPos min, GlobeKind kind, int size, boolean removeEntities)
    {
        if (removeEntities)
            for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(min.getCenter(), min.getCenter()).inflate(size + 2),
                    e -> !(e instanceof Player) && idAt(e.blockPosition()) == idAt(min)))
                entity.discard();
        for (int x = 0; x < size; x++)
            for (int y = 0; y < size; y++)
                for (int z = 0; z < size; z++)
                    if (kind.inside(x, y, z, size))
                    {
                        BlockPos pos = min.offset(x, y, z);
                        // Empty containers first, so clearing doesn't spill their items into the globe.
                        if (level.getBlockEntity(pos) != null)
                            level.removeBlockEntity(pos);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                    }
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

        BlockPos spawn = findSpawn(level, id, GlobeSavedData.get(player.server).sizeOf(id));
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
    private static BlockPos findSpawn(ServerLevel level, int id, int size)
    {
        BlockPos min = interiorMin(id);
        for (int ring = 0; ring < size / 2; ring++)
            for (int dx = -ring; dx <= ring; dx++)
                for (int dz = -ring; dz <= ring; dz++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring)
                        continue;
                    BlockPos spot = standingSpot(level, min.offset(size / 2 + dx, 0, size / 2 + dz), size);
                    if (spot != null)
                        return spot;
                }
        return min.offset(size / 2, size / 2, size / 2);
    }

    /** The lowest spot in this column with ground below and two free, dry blocks above, if there is one. */
    @Nullable
    private static BlockPos standingSpot(ServerLevel level, BlockPos bottom, int size)
    {
        for (int y = 1; y < size - 1; y++)
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

    static void refreshSnapshot(MinecraftServer server, int id)
    {
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null || id < 0)
            return;
        GlobeSavedData data = GlobeSavedData.get(server);
        data.storeSnapshot(id, GlobeSnapshot.capture(level, interiorMin(id), data.kindOf(id), data.sizeOf(id)));
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
    private static final Map<Integer, Integer> ticketed = new HashMap<>();
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
    private static void captureEntities(ServerLevel level, int id, int size)
    {
        BlockPos min = interiorMin(id);
        AABB box = new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + size, min.getY() + size, min.getZ() + size);
        ListTag list = new ListTag();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !(e instanceof Player)))
        {
            ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
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

    private static void setTickets(ServerLevel level, int id, int size, boolean add)
    {
        BlockPos min = interiorMin(id);
        for (int cx = min.getX() >> 4; cx <= (min.getX() + size - 1) >> 4; cx++)
            for (int cz = min.getZ() >> 4; cz <= (min.getZ() + size - 1) >> 4; cz++)
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
    static void onServerTick(ServerTickEvent.Post event)
    {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return;
        int now = server.getTickCount();
        GlobeSavedData data = GlobeSavedData.get(server);

        Set<Integer> active = new HashSet<>();
        lastSeen.entrySet().removeIf(e -> now - e.getValue() > ACTIVE_TICKS);
        active.addAll(lastSeen.keySet());
        for (ServerPlayer player : level.players())
            active.add(idAt(player.blockPosition()));

        for (int id : active)
        {
            // (Re)ticket when a globe becomes active or changes size.
            int size = data.sizeOf(id);
            Integer had = ticketed.get(id);
            if (had == null || had != size)
            {
                if (had != null)
                    setTickets(level, id, had, false);
                setTickets(level, id, size, true);
                ticketed.put(id, size);
            }
        }
        for (Iterator<Map.Entry<Integer, Integer>> it = ticketed.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<Integer, Integer> entry = it.next();
            if (!active.contains(entry.getKey()))
            {
                setTickets(level, entry.getKey(), entry.getValue(), false);
                frames.remove(entry.getKey());
                it.remove();
            }
        }
        if (active.isEmpty())
            return;

        // Without players or forced chunks a level stops ticking its entities after a while; keep it awake.
        level.resetEmptyTime();
        if (now % ENTITY_INTERVAL == 0)
            for (int id : active)
                captureEntities(level, id, data.sizeOf(id));
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
