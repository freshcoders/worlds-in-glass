package com.nick.snowglobe;

import com.nick.snowglobe.net.GlobeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Links the inside of a globe with the world outside it: players inside see the world around the globe block,
 * giant, and the players near it; shakes and knocks from outside are felt inside; and going in or out is a
 * short zoom instead of a cut.
 */
public final class GlobeView
{
    /** Where a player's globe is: kept with the player while they're inside, so the outside can be shown. */
    static final String GLOBE_KEY = SnowGlobeMod.MODID + ":globe";
    /** How much of the outside world is shown around the globe block, in blocks. */
    private static final int RADIUS = 16, BELOW = 6, ABOVE = 16;
    /** Ticks between asking to go in or out and actually going: the client zooms meanwhile. */
    public static final int DIVE_TICKS = 12;

    private GlobeView() {}

    // ---- Who is inside which globe ----

    /** Remembers which globe block a player went in through. */
    static void rememberGlobe(ServerPlayer player, ServerLevel outside, BlockPos globePos, int id)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dim", outside.dimension().location().toString());
        tag.putLong("Pos", globePos.asLong());
        tag.putInt("Id", id);
        player.getPersistentData().put(GLOBE_KEY, tag);
    }

    static void forgetGlobe(ServerPlayer player)
    {
        player.getPersistentData().remove(GLOBE_KEY);
    }

    /** The players inside globe {@code id} right now. */
    static List<ServerPlayer> occupants(MinecraftServer server, int id)
    {
        List<ServerPlayer> list = new ArrayList<>();
        ServerLevel level = server.getLevel(GlobeSpace.LEVEL);
        if (level != null)
            for (ServerPlayer player : level.players())
                if (GlobeSpace.idAt(server, player.blockPosition()) == id)
                    list.add(player);
        return list;
    }

    /** Tells everyone inside a globe that something happened to it outside. */
    static void notifyOccupants(MinecraftServer server, int id, int event, GlobeKind kind)
    {
        for (ServerPlayer player : occupants(server, id))
            GlobeNetwork.send(player, new GlobeNetwork.GlobeEvent(event, kind.ordinal()));
    }

    // ---- The outside world, seen from inside ----

    /** Sends a player inside a globe the world around the globe block, and how to place it around them. */
    static void sendView(ServerPlayer player)
    {
        CompoundTag globe = player.getPersistentData().getCompound(GLOBE_KEY);
        ServerLevel outside = outsideLevel(player.server, globe);
        if (outside == null)
            return;
        BlockPos globePos = BlockPos.of(globe.getLong("Pos"));
        BlockState globeState = outside.getBlockState(globePos);
        if (!(globeState.getBlock() instanceof GlobeBlock block))
            return;
        int id = globe.getInt("Id");
        GlobeSavedData data = GlobeSavedData.get(player.server);
        GlobeSavedData.Entry entry = data.get(id);
        MiniatureLayout layout = MiniatureLayout.of(block.kind(), entry == null ? null : entry.snapshot());

        CompoundTag tag = new CompoundTag();
        tag.putLong("GlobePos", globePos.asLong());
        tag.putInt("Kind", block.kind().ordinal());
        tag.putInt("Phase", data.phaseOf(id));
        tag.putInt("Season", data.seasonOf(id));
        tag.putInt("Turns", GlobeBlock.quarterTurns(globeState.getValue(GlobeBlock.FACING)));
        tag.putInt("Display", globeState.getValue(GlobeBlock.DISPLAY).scale);
        tag.putLong("InteriorMin", GlobeSpace.interiorMin(player.server, id).asLong());
        tag.putFloat("Cell", layout.cell());
        tag.putInt("Scale", layout.scale());
        tag.putDouble("CenterX", layout.center().x);
        tag.putDouble("CenterY", layout.center().y);
        tag.putDouble("CenterZ", layout.center().z);
        tag.putDouble("PivotX", layout.pivot().x);
        tag.putDouble("PivotY", layout.pivot().y);
        tag.putDouble("PivotZ", layout.pivot().z);

        // The blocks around the globe (the globe's own block included: its base looms below you), with their light.
        BlockPos origin = globePos.offset(-RADIUS, -BELOW, -RADIUS);
        int sx = RADIUS * 2 + 1, sy = BELOW + ABOVE + 1, sz = RADIUS * 2 + 1;
        ListTag palette = new ListTag();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] cells = new int[sx * sy * sz];
        byte[] light = new byte[cells.length];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++, i++)
                {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = outside.getBlockState(pos);
                    cells[i] = index.computeIfAbsent(state, s -> {
                        palette.add(NbtUtils.writeBlockState(s));
                        return palette.size() - 1;
                    });
                    light[i] = (byte) (outside.getBrightness(LightLayer.SKY, pos) << 4 | outside.getBrightness(LightLayer.BLOCK, pos));
                }
        light = litFromNeighbours(light, sx, sy, sz);
        // The other globes standing around: their contents, so they show their miniatures (giant) too.
        ListTag globes = new ListTag();
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++)
                {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (pos.equals(globePos) || !(outside.getBlockEntity(pos) instanceof GlobeBlockEntity other) || other.globeId() < 0)
                        continue;
                    GlobeSavedData.Entry otherEntry = data.get(other.globeId());
                    if (otherEntry == null)
                        continue;
                    CompoundTag neighbour = new CompoundTag();
                    neighbour.putLong("Pos", pos.asLong());
                    neighbour.putInt("Phase", data.phaseOf(other.globeId()));
                    neighbour.putInt("Season", data.seasonOf(other.globeId()));
                    neighbour.putBoolean("MusicBox", data.hasMusicBox(other.globeId()));
                    neighbour.put("Snapshot", otherEntry.snapshot().toTag());
                    globes.add(neighbour);
                }
        tag.put("Globes", globes);
        tag.putLong("Origin", origin.asLong());
        tag.putIntArray("Size", new int[] {sx, sy, sz});
        tag.put("Palette", palette);
        tag.putIntArray("Cells", cells);
        tag.putByteArray("Light", light);
        GlobeNetwork.send(player, new GlobeNetwork.OutsideView(tag));
    }

    /**
     * A solid block's own light is dark (it's inside the block); what you see of it is lit by the air around it.
     * So each block takes the brightest of itself and its six neighbours, sky and block light separately.
     */
    private static byte[] litFromNeighbours(byte[] light, int sx, int sy, int sz)
    {
        byte[] lit = new byte[light.length];
        int[][] sides = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++)
                {
                    int sky = 0, block = 0;
                    for (int[] d : sides)
                    {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz)
                            continue;
                        int value = light[(ny * sz + nz) * sx + nx] & 0xFF;
                        sky = Math.max(sky, value >> 4);
                        block = Math.max(block, value & 15);
                    }
                    lit[(y * sz + z) * sx + x] = (byte) (sky << 4 | block);
                }
        return lit;
    }

    /** Sends a player inside a globe where the other players near that globe are, so they appear as giants. */
    private static void sendPlayers(ServerPlayer player)
    {
        CompoundTag globe = player.getPersistentData().getCompound(GLOBE_KEY);
        ServerLevel outside = outsideLevel(player.server, globe);
        if (outside == null)
            return;
        BlockPos globePos = BlockPos.of(globe.getLong("Pos"));
        ListTag list = new ListTag();
        for (ServerPlayer other : outside.players())
        {
            if (other == player || other.distanceToSqr(globePos.getCenter()) > (RADIUS + 4) * (RADIUS + 4))
                continue;
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", other.getUUID());
            tag.putString("Name", other.getGameProfile().getName());
            tag.putDouble("X", other.getX());
            tag.putDouble("Y", other.getY());
            tag.putDouble("Z", other.getZ());
            tag.putFloat("Yaw", other.getYRot());
            tag.putFloat("Pitch", other.getXRot());
            tag.putFloat("Head", other.getYHeadRot());
            tag.putFloat("Body", other.yBodyRot);
            tag.putBoolean("Crouch", other.isCrouching());
            list.add(tag);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Players", list);
        // The animals and monsters around the globe too, so they wander past as giants. Positions relative to the
        // globe block, which keeps them small enough for exact floats.
        tag.put("Mobs", GlobeSpace.entityTags(outside, new AABB(globePos).inflate(RADIUS + 2, ABOVE, RADIUS + 2), globePos));
        GlobeNetwork.send(player, new GlobeNetwork.OutsidePlayers(tag));
    }

    @Nullable
    private static ServerLevel outsideLevel(MinecraftServer server, CompoundTag globe)
    {
        ResourceLocation dim = ResourceLocation.tryParse(globe.getString("Dim"));
        return dim == null || !globe.contains("Pos") ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
    }

    // ---- Knocking on the glass ----

    private static final Map<Integer, Integer> lastKnock = new HashMap<>();

    /** Punching a globe from outside knocks on its glass; everyone inside hears it, loud. */
    static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event)
    {
        if (event.getLevel().isClientSide() || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
                || !(event.getLevel().getBlockEntity(event.getPos()) instanceof GlobeBlockEntity globe)
                || !(event.getLevel() instanceof ServerLevel level))
            return;
        int id = globe.globeId();
        int now = level.getServer().getTickCount();
        if (id < 0 || now - lastKnock.getOrDefault(id, -100) < 6)
            return;
        lastKnock.put(id, now);
        // A glassy tok out here (and a tiny thunder from a storm globe); a deep knock for anyone inside.
        level.blockEvent(event.getPos(), globe.getBlockState().getBlock(), GlobeBlock.EVENT_KNOCK, 0);
        notifyOccupants(level.getServer(), id, GlobeNetwork.GlobeEvent.KNOCKED, globe.kind());
    }

    /**
     * Dev testing: acts as if someone outside knocked on, or shook, the globe this player is inside.
     * False if they aren't inside one.
     */
    static boolean emulateFromOutside(ServerPlayer player, int event)
    {
        CompoundTag globe = player.getPersistentData().getCompound(GLOBE_KEY);
        ServerLevel outside = outsideLevel(player.server, globe);
        if (outside == null)
            return false;
        BlockPos pos = BlockPos.of(globe.getLong("Pos"));
        if (!(outside.getBlockEntity(pos) instanceof GlobeBlockEntity globeEntity) || !(outside.getBlockState(pos).getBlock() instanceof GlobeBlock block))
            return false;
        if (event == GlobeNetwork.GlobeEvent.SHAKEN)
            outside.blockEvent(pos, block, GlobeBlock.EVENT_SHAKE, 0);
        else
            outside.blockEvent(pos, block, GlobeBlock.EVENT_KNOCK, 0);
        notifyOccupants(player.server, globeEntity.globeId(), event, block.kind());
        return true;
    }

    // ---- Going in and out: a short zoom first ----

    private record Transit(boolean in, ServerLevel level, BlockPos globePos, int due) {}

    private static final Map<UUID, Transit> transits = new HashMap<>();

    /** Starts going into a globe: the player's view zooms in, and they're moved in a moment later. */
    static void startEntering(ServerPlayer player, ServerLevel level, BlockPos globePos, GlobeKind kind)
    {
        if (transits.containsKey(player.getUUID()))
            return;
        transits.put(player.getUUID(), new Transit(true, level, globePos.immutable(), player.server.getTickCount() + DIVE_TICKS));
        GlobeNetwork.send(player, new GlobeNetwork.GlobeEvent(GlobeNetwork.GlobeEvent.DIVE_IN, kind.ordinal()));
    }

    /** Starts leaving a globe: the view zooms out, and the player is back outside a moment later. */
    static void startLeaving(ServerPlayer player)
    {
        if (transits.containsKey(player.getUUID()))
            return;
        transits.put(player.getUUID(), new Transit(false, null, null, player.server.getTickCount() + DIVE_TICKS));
        GlobeNetwork.send(player, new GlobeNetwork.GlobeEvent(GlobeNetwork.GlobeEvent.DIVE_OUT, 0));
    }

    static void tick(MinecraftServer server)
    {
        int now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Transit>> it = transits.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<UUID, Transit> entry = it.next();
            Transit transit = entry.getValue();
            if (now < transit.due())
                continue;
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null)
                continue;
            if (transit.in() && transit.level().getBlockEntity(transit.globePos()) instanceof GlobeBlockEntity globe)
                GlobeSpace.enter(player, globe);
            else if (!transit.in())
                GlobeSpace.leave(player);
            GlobeNetwork.send(player, new GlobeNetwork.GlobeEvent(GlobeNetwork.GlobeEvent.ARRIVED, 0));
        }

        // Keep the view up to date for everyone inside: players often, the world now and then
        // (so turning the globe, or building next to it, shows up inside too).
        ServerLevel level = server.getLevel(GlobeSpace.LEVEL);
        if (level == null)
            return;
        for (ServerPlayer player : level.players())
        {
            if (!player.getPersistentData().contains(GLOBE_KEY))
                continue;
            if (now % 4 == 0)
                sendPlayers(player);
            if (now % 100 == 0)
                sendView(player);
        }
    }

    static void clear()
    {
        transits.clear();
        lastKnock.clear();
    }
}
