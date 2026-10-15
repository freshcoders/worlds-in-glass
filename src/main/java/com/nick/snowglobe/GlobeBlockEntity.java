package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Remembers which globe space a globe block opens, and holds the latest snapshot for drawing the miniature. */
public class GlobeBlockEntity extends BlockEntity
{
    private int globeId = -1;
    @Nullable
    private GlobeSnapshot snapshot;
    private long snapshotVersion;
    /** Server: set when the next update packet must include the blocks. */
    private boolean blocksChanged = true;
    /** Server: version of the mob positions last sent. */
    private long entityVersion;
    /** Client: mob positions received and not yet taken by the renderer. */
    @Nullable
    private ListTag entities;
    /** Client: whatever the renderer keeps per globe (the miniature mobs). Not touched on the server. */
    public Object renderState;
    /** Client: game time this globe was last shaken. */
    public long shakeStart = Long.MIN_VALUE;

    public GlobeBlockEntity(BlockPos pos, BlockState state)
    {
        super(SnowGlobeMod.GLOBE_BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    public GlobeSnapshot getSnapshot()
    {
        return snapshot;
    }

    /** The id of this globe's build space, or -1 if it has none yet. */
    public int globeId()
    {
        return globeId;
    }

    public GlobeKind kind()
    {
        return getBlockState().getBlock() instanceof GlobeBlock block ? block.kind() : GlobeKind.SNOW_GLOBE;
    }

    /** Gives this globe its own build space if it has none yet (empty), and returns the globe id. */
    public int ensureGlobe(MinecraftServer server)
    {
        return ensureGlobe(server, false);
    }

    /** As above; a new build space gets the kind's starter scene if {@code withScene}. */
    public int ensureGlobe(MinecraftServer server, boolean withScene)
    {
        if (globeId < 0)
        {
            globeId = GlobeSpace.create(server, kind(), withScene);
            pullSnapshot(server);
            setChanged();
        }
        return globeId;
    }

    /** Copies the newest snapshot of this globe's space; true if it changed. */
    private boolean pullSnapshot(MinecraftServer server)
    {
        GlobeSavedData.Entry entry = GlobeSavedData.get(server).get(globeId);
        if (entry == null || entry.version() == snapshotVersion)
            return false;
        snapshot = entry.snapshot();
        snapshotVersion = entry.version();
        return true;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GlobeBlockEntity globe)
    {
        MinecraftServer server = level.getServer();
        if (globe.globeId < 0 || server == null)
            return;
        GlobeSpace.markSeen(server, globe.globeId);
        boolean send = false;

        // Mob positions: a few times a second, when they moved.
        GlobeSpace.EntityFrame frame = GlobeSpace.entityFrame(globe.globeId);
        if (frame != null && frame.version() != globe.entityVersion)
        {
            globe.entityVersion = frame.version();
            send = true;
        }
        // Blocks: about once a second, spread out over ticks so many globes don't all update at once.
        if (Math.floorMod(level.getGameTime() + pos.asLong(), 20) == 0)
        {
            if (globe.pullSnapshot(server))
            {
                globe.blocksChanged = true;
                globe.setChanged();
                send = true;
            }
            // Checked every time, not only on changes: globes from before display sizes existed need it too.
            globe.updateDisplaySize();
        }
        if (send)
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    /** Server: makes the globe look small, medium or large to match how big its build space is now. */
    void updateDisplaySize()
    {
        if (globeId < 0 || level == null || level.getServer() == null)
            return;
        BlockState state = getBlockState();
        if (!state.hasProperty(GlobeBlock.DISPLAY))
            return;
        DisplaySize wanted = DisplaySize.forGlobeSize(GlobeSavedData.get(level.getServer()).sizeOf(globeId));
        if (state.getValue(GlobeBlock.DISPLAY) != wanted)
            level.setBlock(worldPosition, state.setValue(GlobeBlock.DISPLAY, wanted), Block.UPDATE_ALL);
    }

    /** Mob positions received since the renderer last looked, or null if none came in. Client side. */
    @Nullable
    public ListTag takeEntityUpdate()
    {
        ListTag update = entities;
        entities = null;
        return update;
    }

    private void readGlobe(CompoundTag tag)
    {
        // Update packets carry only what changed, so leave out fields alone.
        if (tag.contains("GlobeId"))
            globeId = tag.getInt("GlobeId");
        if (tag.contains("Snapshot"))
        {
            snapshot = GlobeSnapshot.fromTag(tag.getCompound("Snapshot"));
            snapshotVersion = tag.getLong("SnapshotVersion");
        }
        if (tag.contains("Entities"))
            entities = tag.getList("Entities", Tag.TAG_COMPOUND);
    }

    private CompoundTag writeGlobe(CompoundTag tag)
    {
        if (globeId >= 0)
            tag.putInt("GlobeId", globeId);
        if (snapshot != null)
        {
            tag.put("Snapshot", snapshot.toTag());
            tag.putLong("SnapshotVersion", snapshotVersion);
        }
        return tag;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries)
    {
        super.loadAdditional(tag, registries);
        readGlobe(tag);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries)
    {
        super.saveAdditional(tag, registries);
        writeGlobe(tag);
    }

    // The item keeps the globe's id and contents: picked up and placed again, it opens the same space.

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components)
    {
        super.collectImplicitComponents(components);
        if (globeId >= 0)
            components.set(SnowGlobeMod.GLOBE_DATA.get(), CustomData.of(writeGlobe(new CompoundTag())));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput components)
    {
        super.applyImplicitComponents(components);
        CustomData data = components.get(SnowGlobeMod.GLOBE_DATA.get());
        if (data != null)
            readGlobe(data.copyTag());
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag)
    {
        super.removeComponentsFromTag(tag);
        tag.remove("GlobeId");
        tag.remove("Snapshot");
        tag.remove("SnapshotVersion");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries)
    {
        CompoundTag tag = writeGlobe(new CompoundTag());
        GlobeSpace.EntityFrame frame = GlobeSpace.entityFrame(globeId);
        if (frame != null)
            tag.put("Entities", frame.entities());
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries)
    {
        readGlobe(tag);
    }

    /** Sends the mob positions, plus the blocks only when those changed (they're much bigger). */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this, (be, registries) -> {
            CompoundTag tag = new CompoundTag();
            if (blocksChanged)
            {
                writeGlobe(tag);
                blocksChanged = false;
            }
            GlobeSpace.EntityFrame frame = GlobeSpace.entityFrame(globeId);
            if (frame != null)
                tag.put("Entities", frame.entities());
            return tag;
        });
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries)
    {
        readGlobe(packet.getTag());
    }
}
