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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The globe dimension: an empty world where every globe gets its own walled-in build space.
 * Each globe has a slot along the x axis (see {@link GlobeSavedData}), so the globe a player is in follows
 * from their position.
 */
public final class GlobeSpace
{
    public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(SnowGlobeMod.MODID, "globe"));
    /** Build space sizes (blocks along each axis). New globes start at the smallest; imports grow them to fit. */
    public static final List<Integer> SIZES = List.of(16, 24, 32, 48, 64, 96, 128, 192, 256);
    public static final int DEFAULT_SIZE = 16;
    public static final int MAX_SIZE = 256;
    private static final int BASE_Y = 64;
    /** Where a player came from before going in (from before globes could be nested: a single place). */
    private static final String RETURN_KEY = SnowGlobeMod.MODID + ":return";
    /** Where a player came from, one entry per globe they're in: the innermost last. */
    private static final String RETURNS_KEY = SnowGlobeMod.MODID + ":returns";
    /** The globes a player is in around the one they're in now (see {@link GlobeView#GLOBE_KEY}), innermost last. */
    static final String OUTER_KEY = SnowGlobeMod.MODID + ":outer_globes";

    private GlobeSpace() {}

    /** Lowest corner of globe {@code id}'s build space (the walls sit just outside it). */
    public static BlockPos interiorMin(MinecraftServer server, int id)
    {
        return new BlockPos(GlobeSavedData.get(server).slotStart(id) + 1, BASE_Y + 1, 1);
    }

    /** The globe whose space holds this position, or -1. */
    public static int idAt(MinecraftServer server, BlockPos pos)
    {
        return GlobeSavedData.get(server).idAt(pos.getX());
    }

    // ---- Walking a globe's blocks quickly ----

    @FunctionalInterface
    public interface BlockVisitor
    {
        /** (x, y, z) relative to the build space's corner. */
        void visit(int x, int y, int z, BlockState state, BlockPos worldPos);
    }

    /**
     * Visits every non-air block in the size³ cube from {@code min}, skipping empty chunk sections entirely,
     * so even a 256-block globe that's mostly air is quick.
     */
    public static void forEachBlock(ServerLevel level, BlockPos min, int size, BlockVisitor visitor)
    {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxX = min.getX() + size - 1, maxY = min.getY() + size - 1, maxZ = min.getZ() + size - 1;
        for (int cx = min.getX() >> 4; cx <= maxX >> 4; cx++)
            for (int cz = min.getZ() >> 4; cz <= maxZ >> 4; cz++)
            {
                LevelChunk chunk = level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = min.getY() >> 4; sy <= maxY >> 4; sy++)
                {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections.length || sections[index].hasOnlyAir())
                        continue;
                    LevelChunkSection section = sections[index];
                    int x0 = Math.max(cx << 4, min.getX()), x1 = Math.min((cx << 4) + 15, maxX);
                    int y0 = Math.max(sy << 4, min.getY()), y1 = Math.min((sy << 4) + 15, maxY);
                    int z0 = Math.max(cz << 4, min.getZ()), z1 = Math.min((cz << 4) + 15, maxZ);
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++)
                            for (int x = x0; x <= x1; x++)
                            {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!state.isAir())
                                    visitor.visit(x - min.getX(), y - min.getY(), z - min.getZ(), state, pos.set(x, y, z));
                            }
                }
            }
    }

    // ---- Walls ----

    /** Quick test for cells that could be part of the wall: those within two blocks of the space's edge. */
    private static boolean nearEdge(GlobeKind kind, int size, int x, int y, int z)
    {
        double c = size / 2.0;
        double dx = x + 0.5 - c, dy = y + 0.5 - c, dz = z + 0.5 - c;
        return switch (kind.space)
        {
            case CUBE -> x < 1 || y < 1 || z < 1 || x >= size - 1 || y >= size - 1 || z >= size - 1;
            case SPHERE -> Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - c) < 2;
            case BOTTLE -> Math.abs(Math.sqrt(dy * dy + dz * dz) - GlobeKind.bottleRadius(size)) < 2 || x < 1 || x >= size - 1;
            case JAR -> Math.abs(Math.sqrt(dx * dx + dz * dz) - GlobeKind.jarRadius(size)) < 2 || y < 1 || y >= size - 1;
        };
    }

    /** True for cells outside the build space that touch it, even diagonally: together they seal it in. */
    private static boolean shell(GlobeKind kind, int size, int x, int y, int z)
    {
        if (!nearEdge(kind, size, x, y, z) || kind.inside(x, y, z, size))
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

    // ---- Making, resizing and emptying globes ----

    /**
     * Builds a new globe's glass walls, and its starter scene if asked (only for creative players: in survival a
     * scene would be blocks and mobs out of nothing), and returns its id.
     */
    public static int create(MinecraftServer server, GlobeKind kind, boolean withScene)
    {
        GlobeSavedData data = GlobeSavedData.get(server);
        int id = data.allocateId(kind);
        data.setSize(id, DEFAULT_SIZE);
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return id;

        BlockPos min = interiorMin(server, id);
        buildShell(level, min, kind, DEFAULT_SIZE, SnowGlobeMod.GLOBE_WALL.get().defaultBlockState());
        if (withScene)
            GlobeScenes.build(level, min, kind);

        data.storeSnapshot(id, GlobeSnapshot.capture(level, min, kind, DEFAULT_SIZE));
        return id;
    }

    /**
     * Gives a globe a new size: new walls, and everything inside (blocks, their contents, mobs, players)
     * moved so it stays centred. A globe that outgrows its slot moves to a new, roomier one.
     * Shrinking cuts off what no longer fits.
     */
    public static void resize(MinecraftServer server, int id, int newSize)
    {
        resize(server, id, newSize, false);
    }

    /**
     * Moves a globe from before slots existed (only 64 blocks from its neighbours, so you could see them from
     * inside) to a slot of its own, far from the others. Everything inside moves along.
     */
    static void giveOwnSlot(MinecraftServer server, int id)
    {
        GlobeSavedData data = GlobeSavedData.get(server);
        if (data.capacity(id) < GlobeSavedData.SLOT_WIDTH - 2)
            resize(server, id, data.sizeOf(id), true);
    }

    private static void resize(MinecraftServer server, int id, int newSize, boolean newSlot)
    {
        ServerLevel level = server.getLevel(LEVEL);
        GlobeSavedData data = GlobeSavedData.get(server);
        int oldSize = data.sizeOf(id);
        if (level == null || oldSize == newSize && !newSlot)
            return;
        GlobeKind kind = data.kindOf(id);
        BlockPos oldMin = interiorMin(server, id);

        // Take everything out: blocks with their data, and the entities in the space.
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, CompoundTag> blockData = new HashMap<>();
        forEachBlock(level, oldMin, oldSize, (x, y, z, state, pos) -> {
            if (!kind.inside(x, y, z, oldSize))
                return;
            BlockPos cell = new BlockPos(x, y, z);
            states.put(cell, state);
            BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            if (be != null)
                blockData.put(cell, be.saveWithoutMetadata(level.registryAccess()));
        });
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, spaceBox(oldMin, oldSize));
        clearSpace(level, oldMin, kind, oldSize, false);
        buildShell(level, oldMin, kind, oldSize, Blocks.AIR.defaultBlockState());

        if (newSlot || newSize > data.capacity(id))
            data.giveSlot(id);
        BlockPos min = interiorMin(server, id);

        // Put it back, shifted so it stays in the middle (a terrarium or jar keeps its floor where it is).
        int shift = (newSize - oldSize) / 2;
        int shiftY = kind.space.hasFloor() ? 0 : shift;
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
        int moveX = min.getX() - oldMin.getX() + shift, moveY = shiftY, moveZ = min.getZ() - oldMin.getZ() + shift;
        for (Entity entity : entities)
        {
            if (entity instanceof ServerPlayer player)
                player.teleportTo(level, player.getX() + moveX, player.getY() + moveY, player.getZ() + moveZ, player.getYRot(), player.getXRot());
            else
                entity.teleportTo(entity.getX() + moveX, entity.getY() + moveY, entity.getZ() + moveZ);
        }

        data.setSize(id, newSize);
        data.storeSnapshot(id, GlobeSnapshot.capture(level, min, kind, newSize));
    }

    /** Where a block of a globe ends up when it's resized: shifted to stay centred (a terrarium keeps its floor). */
    private static BlockPos resizedCell(GlobeKind kind, int oldSize, int newSize, int x, int y, int z)
    {
        int shift = (newSize - oldSize) / 2;
        return new BlockPos(x + shift, y + (kind.space.hasFloor() ? 0 : shift), z + shift);
    }

    /** How many blocks resizing to {@code newSize} would cut off (0 when growing). */
    public static int blocksLostByResize(MinecraftServer server, int id, int newSize)
    {
        ServerLevel level = server.getLevel(LEVEL);
        GlobeSavedData data = GlobeSavedData.get(server);
        int oldSize = data.sizeOf(id);
        if (level == null || newSize >= oldSize)
            return 0;
        GlobeKind kind = data.kindOf(id);
        int[] lost = {0};
        forEachBlock(level, interiorMin(server, id), oldSize, (x, y, z, state, pos) -> {
            BlockPos to = resizedCell(kind, oldSize, newSize, x, y, z);
            if (kind.inside(x, y, z, oldSize) && !kind.inside(to.getX(), to.getY(), to.getZ(), newSize))
                lost[0]++;
        });
        return lost[0];
    }

    /** True if nothing has been built in a globe. */
    public static boolean isEmpty(MinecraftServer server, int id)
    {
        ServerLevel level = server.getLevel(LEVEL);
        GlobeSavedData data = GlobeSavedData.get(server);
        if (level == null)
            return true;
        GlobeKind kind = data.kindOf(id);
        int size = data.sizeOf(id);
        boolean[] found = {false};
        forEachBlock(level, interiorMin(server, id), size, (x, y, z, state, pos) -> {
            if (kind.inside(x, y, z, size))
                found[0] = true;
        });
        return !found[0];
    }

    static AABB spaceBox(BlockPos min, int size)
    {
        return new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + size, min.getY() + size, min.getZ() + size);
    }

    /** Empties a globe's build space: its blocks, and (unless asked to keep them) the mobs in it. */
    static void clearSpace(ServerLevel level, BlockPos min, GlobeKind kind, int size, boolean removeEntities)
    {
        if (removeEntities)
            for (Entity entity : level.getEntitiesOfClass(Entity.class, spaceBox(min, size), e -> !(e instanceof Player)))
                entity.discard();
        List<BlockPos> filled = new ArrayList<>();
        forEachBlock(level, min, size, (x, y, z, state, pos) -> {
            if (kind.inside(x, y, z, size))
                filled.add(pos.immutable());
        });
        for (BlockPos pos : filled)
        {
            // Empty containers first, so clearing doesn't spill their items into the globe.
            if (level.getBlockEntity(pos) != null)
                level.removeBlockEntity(pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        }
    }

    // ---- Going in and out ----

    /**
     * Takes a player into a globe, with any animals they're leading. Globes can stand inside globes: going into one
     * from inside another remembers both, and leaving takes you back out one at a time.
     */
    public static void enter(ServerPlayer player, GlobeBlockEntity globe)
    {
        ServerLevel level = player.server.getLevel(LEVEL);
        if (level == null || !(player.level() instanceof ServerLevel outside))
            return;
        boolean nested = outside.dimension() == LEVEL;
        int id = globe.ensureGlobe(player.server);
        // A globe can't be entered from inside itself (it would have to stand inside its own build space).
        if (nested && idAt(player.server, globe.getBlockPos()) == id)
            return;
        giveOwnSlot(player.server, id);

        CompoundTag back = new CompoundTag();
        back.putString("Dim", player.level().dimension().location().toString());
        back.putDouble("X", player.getX());
        back.putDouble("Y", player.getY());
        back.putDouble("Z", player.getZ());
        back.putFloat("Yaw", player.getYRot());
        back.putFloat("Pitch", player.getXRot());
        // Inside another globe: also remember where in that globe, so coming back finds the spot even if its
        // world has moved to another slot since (when it grew, for one).
        int outerId = nested ? idAt(player.server, player.blockPosition()) : -1;
        if (outerId >= 0)
        {
            BlockPos outerMin = interiorMin(player.server, outerId);
            back.putInt("Id", outerId);
            back.putDouble("RX", player.getX() - outerMin.getX());
            back.putDouble("RY", player.getY() - outerMin.getY());
            back.putDouble("RZ", player.getZ() - outerMin.getZ());
        }
        CompoundTag data = player.getPersistentData();
        ListTag returns = data.getList(RETURNS_KEY, Tag.TAG_COMPOUND);
        if (returns.isEmpty() && data.contains(RETURN_KEY))
            returns.add(data.getCompound(RETURN_KEY));
        data.remove(RETURN_KEY);
        returns.add(back);
        data.put(RETURNS_KEY, returns);
        if (data.contains(GlobeView.GLOBE_KEY))
        {
            ListTag outer = data.getList(OUTER_KEY, Tag.TAG_COMPOUND);
            outer.add(data.getCompound(GlobeView.GLOBE_KEY));
            data.put(OUTER_KEY, outer);
        }

        List<net.minecraft.world.entity.Mob> friends = GlobeCreatures.leashedTo(player);
        BlockPos spawn = findSpawn(level, interiorMin(player.server, id), GlobeSavedData.get(player.server).sizeOf(id));
        player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
        if (GlobeCreatures.bringAlong(friends, player))
            GlobeAdvancements.award(player, "plus_one");
        player.displayClientMessage(Component.translatable("snowglobe.message.entered"), false);
        GlobeView.rememberGlobe(player, outside, globe.getBlockPos(), id);
        GlobeView.sendView(player);
        GlobeAdvancements.award(player, "step_inside");
        if (nested)
            GlobeAdvancements.award(player, "deeper_still");
    }

    public static void leave(ServerPlayer player)
    {
        MinecraftServer server = player.server;
        if (player.level().dimension() == LEVEL)
            refreshSnapshot(server, idAt(server, player.blockPosition()));

        // Out one level: to where they came in from (inside an outer globe, if this one stood in one).
        CompoundTag data = player.getPersistentData();
        ListTag returns = data.getList(RETURNS_KEY, Tag.TAG_COMPOUND);
        CompoundTag back = !returns.isEmpty() ? (CompoundTag) returns.remove(returns.size() - 1) : data.getCompound(RETURN_KEY);
        data.remove(RETURN_KEY);
        if (returns.isEmpty())
            data.remove(RETURNS_KEY);
        else
            data.put(RETURNS_KEY, returns);
        ListTag outer = data.getList(OUTER_KEY, Tag.TAG_COMPOUND);
        if (!outer.isEmpty())
        {
            data.put(GlobeView.GLOBE_KEY, outer.remove(outer.size() - 1));
            if (outer.isEmpty())
                data.remove(OUTER_KEY);
            else
                data.put(OUTER_KEY, outer);
        }
        else
            GlobeView.forgetGlobe(player);

        List<net.minecraft.world.entity.Mob> friends = GlobeCreatures.leashedTo(player);
        ResourceLocation dim = ResourceLocation.tryParse(back.getString("Dim"));
        ServerLevel target = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (target == null || target.dimension() == LEVEL && !data.contains(GlobeView.GLOBE_KEY))
        {
            // No way back recorded (e.g. after a death inside): go to world spawn.
            data.remove(OUTER_KEY);
            data.remove(RETURNS_KEY);
            GlobeView.forgetGlobe(player);
            target = server.overworld();
            BlockPos spawn = target.getSharedSpawnPos();
            player.teleportTo(target, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
            return;
        }
        double x = back.getDouble("X"), y = back.getDouble("Y"), z = back.getDouble("Z");
        if (target.dimension() == LEVEL)
        {
            // Back into an outer globe: wherever its world is now, and inside it (never out in the empty dimension).
            int outerId = back.contains("Id") ? back.getInt("Id") : idAt(server, BlockPos.containing(x, y, z));
            if (outerId >= 0)
            {
                BlockPos outerMin = interiorMin(server, outerId);
                if (back.contains("RX"))
                {
                    x = outerMin.getX() + back.getDouble("RX");
                    y = outerMin.getY() + back.getDouble("RY");
                    z = outerMin.getZ() + back.getDouble("RZ");
                }
                GlobeSavedData saved = GlobeSavedData.get(server);
                int size = saved.sizeOf(outerId);
                BlockPos cell = BlockPos.containing(x, y, z).subtract(outerMin);
                if (!saved.kindOf(outerId).inside(cell.getX(), cell.getY(), cell.getZ(), size))
                {
                    BlockPos spot = findSpawn(target, outerMin, size);
                    x = spot.getX() + 0.5;
                    y = spot.getY();
                    z = spot.getZ() + 0.5;
                }
            }
        }
        player.teleportTo(target, x, y, z, back.getFloat("Yaw"), back.getFloat("Pitch"));
        GlobeCreatures.bringAlong(friends, player);
        // Back inside the outer globe: show its outside again.
        if (target.dimension() == LEVEL)
            GlobeView.sendView(player);
    }

    /** Forgets every way back out of globes (after a death inside: waking up outside replaces them). */
    static void forgetWayBack(ServerPlayer player)
    {
        CompoundTag data = player.getPersistentData();
        data.remove(RETURN_KEY);
        data.remove(RETURNS_KEY);
        data.remove(OUTER_KEY);
        GlobeView.forgetGlobe(player);
    }

    // ---- Snow that settles in a snow globe ----

    /**
     * A snow globe shaken into winter: snow settles on the open ground and roofs inside, a thin layer (or one more
     * on snow already lying). It melts again after a few minutes, or straight away when shaken into spring.
     */
    static void settleSnow(MinecraftServer server, int id)
    {
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return;
        GlobeSavedData data = GlobeSavedData.get(server);
        GlobeKind kind = data.kindOf(id);
        int size = data.sizeOf(id);
        BlockPos min = interiorMin(server, id);
        java.util.Map<Long, Long> snow = data.settledSnow(id);
        long now = level.getGameTime();
        net.minecraft.util.RandomSource random = level.random;
        // A sample of columns: plenty for a small globe, and a light dusting rather than a long freeze for a huge one.
        int columns = Math.min(size * size * 2 / 5, 900);
        for (int i = 0; i < columns; i++)
        {
            int x = random.nextInt(size), z = random.nextInt(size);
            for (int y = size - 1; y >= 0; y--)
            {
                if (!kind.inside(x, y, z, size))
                    continue;
                BlockPos pos = min.offset(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir())
                    continue;
                long meltAt = now + 2400 + random.nextInt(3600);
                if (state.is(Blocks.SNOW))
                {
                    int layers = state.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
                    if (layers < 3 && snow.containsKey(pos.asLong()))
                    {
                        level.setBlock(pos, state.setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, layers + 1), Block.UPDATE_ALL);
                        snow.put(pos.asLong(), meltAt);
                    }
                }
                else
                {
                    BlockPos above = pos.above();
                    BlockState layer = Blocks.SNOW.defaultBlockState();
                    if (kind.inside(x, y + 1, z, size) && level.getBlockState(above).isAir() && layer.canSurvive(level, above))
                    {
                        level.setBlock(above, layer, Block.UPDATE_ALL);
                        snow.put(above.asLong(), meltAt);
                    }
                }
                break;
            }
        }
        data.setDirty();
        refreshSnapshot(server, id);
    }

    /** Shaken into spring: the settled snow melts within the next minute. */
    static void thaw(MinecraftServer server, int id)
    {
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return;
        GlobeSavedData data = GlobeSavedData.get(server);
        long now = level.getGameTime();
        data.settledSnow(id).replaceAll((pos, meltAt) -> Math.min(meltAt, now + 40 + level.random.nextInt(1000)));
        data.setDirty();
    }

    /** Melts settled snow that's due, a layer at a time, in globes that are loaded. */
    private static void meltSnow(MinecraftServer server, ServerLevel level)
    {
        GlobeSavedData data = GlobeSavedData.get(server);
        long now = level.getGameTime();
        for (int id : new ArrayList<>(data.globesWithSnow()))
        {
            java.util.Map<Long, Long> snow = data.settledSnow(id);
            boolean changed = false;
            for (Iterator<java.util.Map.Entry<Long, Long>> it = snow.entrySet().iterator(); it.hasNext(); )
            {
                java.util.Map.Entry<Long, Long> entry = it.next();
                BlockPos pos = BlockPos.of(entry.getKey());
                if (entry.getValue() > now || !level.isLoaded(pos))
                    continue;
                BlockState state = level.getBlockState(pos);
                if (!state.is(Blocks.SNOW))
                {
                    it.remove();
                    continue;
                }
                int layers = state.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
                if (layers > 1)
                {
                    level.setBlock(pos, state.setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, layers - 1), Block.UPDATE_ALL);
                    entry.setValue(now + 200 + level.random.nextInt(600));
                }
                else
                {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    it.remove();
                }
                changed = true;
            }
            if (changed)
                data.setDirty();
        }
    }

    /** Finds a free spot to stand, starting from the middle of the globe and working outwards. */
    private static BlockPos findSpawn(ServerLevel level, BlockPos min, int size)
    {
        // Look near the middle first; a big empty globe would otherwise mean checking every column.
        for (int ring = 0; ring < Math.min(size / 2, 24); ring++)
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
        data.storeSnapshot(id, GlobeSnapshot.capture(level, interiorMin(server, id), data.kindOf(id), data.sizeOf(id)));
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
    private static final Map<Integer, Ticketed> ticketed = new HashMap<>();
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

    /** Positions of the living things in a globe, players too, relative to the build space. */
    private static void captureEntities(ServerLevel level, int id, BlockPos min, int size)
    {
        ListTag list = entityTags(level, spaceBox(min, size), min, true);
        EntityFrame old = frames.get(id);
        if (old == null || !old.entities().equals(list))
            frames.put(id, new EntityFrame(list, old == null ? 1 : old.version() + 1));
    }

    /**
     * The living things (not players) in a box, as sent to clients for their stand-in copies: type, id, position
     * relative to {@code origin}, turn, and what they look like.
     */
    static ListTag entityTags(ServerLevel level, AABB box, BlockPos origin)
    {
        return entityTags(level, box, origin, false);
    }

    /**
     * As above, with the players in the box too if asked: who they are (for their skin), and whether they're
     * crouching. Players in a globe show up tiny in its miniature.
     */
    static ListTag entityTags(ServerLevel level, AABB box, BlockPos origin, boolean players)
    {
        ListTag list = new ListTag();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && (players ? !e.isSpectator() : !(e instanceof Player))))
        {
            ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            CompoundTag tag = new CompoundTag();
            tag.putString("T", type.toString());
            tag.putInt("I", entity.getId());
            tag.putFloat("X", (float) (entity.getX() - origin.getX()));
            tag.putFloat("Y", (float) (entity.getY() - origin.getY()));
            tag.putFloat("Z", (float) (entity.getZ() - origin.getZ()));
            tag.putFloat("B", entity.yBodyRot);
            tag.putFloat("H", entity.yHeadRot);
            if (entity.isInWater())
                tag.putBoolean("W", true);
            if (entity instanceof Player player)
            {
                tag.putUUID("P", player.getUUID());
                tag.putString("N", player.getGameProfile().getName());
                tag.putFloat("Xr", player.getXRot());
                if (player.isCrouching())
                    tag.putBoolean("C", true);
                list.add(tag);
                continue;
            }
            CompoundTag looks = looks(entity);
            if (!looks.isEmpty())
                tag.put("L", looks);
            list.add(tag);
        }
        return list;
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

    /** Where a live globe's tickets are, so they can be taken back exactly (even after it grows or moves). */
    private record Ticketed(BlockPos min, int size) {}

    private static void setTickets(ServerLevel level, Ticketed area, boolean add)
    {
        BlockPos min = area.min();
        for (int cx = min.getX() >> 4; cx <= (min.getX() + area.size() - 1) >> 4; cx++)
            for (int cz = min.getZ() >> 4; cz <= (min.getZ() + area.size() - 1) >> 4; cz++)
            {
                ChunkPos chunk = new ChunkPos(cx, cz);
                // Distance 2 gives ticket level 31: the chunk's entities tick.
                if (add)
                    level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk);
                else
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, chunk);
            }
    }

    /** Globes this size or smaller re-capture their blocks every second; bigger ones only while someone builds inside. */
    private static final int LIVE_CAPTURE_SIZE = 64;

    /**
     * Runs the live globes: keeps their chunks ticking, captures their mobs a few times a second
     * and their blocks once a second (big globes: every 5 seconds while someone is inside), so the miniatures
     * follow along.
     */
    static void onServerTick(ServerTickEvent.Post event)
    {
        MinecraftServer server = event.getServer();
        GlobeView.tick(server);
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null)
            return;
        int now = server.getTickCount();
        GlobeSavedData data = GlobeSavedData.get(server);
        if (now % 40 == 0)
            meltSnow(server, level);
        if (now % 10 == 0)
            catchFalling(server, level, data);

        Set<Integer> occupied = new HashSet<>();
        for (ServerPlayer player : level.players())
        {
            int id = idAt(server, player.blockPosition());
            if (id >= 0)
                occupied.add(id);
        }
        Set<Integer> active = new HashSet<>(occupied);
        lastSeen.entrySet().removeIf(e -> now - e.getValue() > ACTIVE_TICKS);
        active.addAll(lastSeen.keySet());

        for (int id : active)
        {
            // (Re)ticket when a globe becomes active, grows, or moves to a new slot.
            Ticketed area = new Ticketed(interiorMin(server, id), data.sizeOf(id));
            Ticketed had = ticketed.get(id);
            if (!area.equals(had))
            {
                if (had != null)
                    setTickets(level, had, false);
                setTickets(level, area, true);
                ticketed.put(id, area);
            }
        }
        for (Iterator<Map.Entry<Integer, Ticketed>> it = ticketed.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<Integer, Ticketed> entry = it.next();
            if (!active.contains(entry.getKey()))
            {
                setTickets(level, entry.getValue(), false);
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
                captureEntities(level, id, ticketed.get(id).min(), data.sizeOf(id));
        for (int id : active)
        {
            boolean big = data.sizeOf(id) > LIVE_CAPTURE_SIZE;
            if (!big && now % 20 == 0 || big && occupied.contains(id) && now % 100 == 0)
                refreshSnapshot(server, id);
        }
    }

    /**
     * A safety net: anyone below every globe's floor in the globe dimension has somehow got out of a globe (a world
     * that moved, a glitch) and is falling into the void. Put them back inside the globe they're under, or out.
     */
    private static void catchFalling(MinecraftServer server, ServerLevel level, GlobeSavedData data)
    {
        for (ServerPlayer player : List.copyOf(level.players()))
        {
            if (player.getY() > BASE_Y - 3 || player.isSpectator())
                continue;
            int id = idAt(server, player.blockPosition());
            player.fallDistance = 0;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            if (id >= 0)
            {
                BlockPos spot = findSpawn(level, interiorMin(server, id), data.sizeOf(id));
                player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(), player.getXRot());
            }
            else
                leave(player);
        }
    }

    /** Forget live globes when a world closes, so they don't leak into the next one opened. */
    static void onServerStopped(ServerStoppedEvent event)
    {
        lastSeen.clear();
        ticketed.clear();
        frames.clear();
        GlobeView.clear();
    }
}
