package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
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

    public GlobeBlockEntity(BlockPos pos, BlockState state)
    {
        super(SnowGlobeMod.GLOBE_BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    public GlobeSnapshot getSnapshot()
    {
        return snapshot;
    }

    public GlobeKind kind()
    {
        return getBlockState().getBlock() instanceof GlobeBlock block ? block.kind() : GlobeKind.SNOW_GLOBE;
    }

    /** Gives this globe its own build space if it has none yet, and returns the globe id. */
    public int ensureGlobe(MinecraftServer server)
    {
        if (globeId < 0)
        {
            globeId = GlobeSpace.create(server, kind());
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
        if (Math.floorMod(level.getGameTime() + pos.asLong(), 20) == 0 && globe.pullSnapshot(server))
        {
            globe.blocksChanged = true;
            globe.setChanged();
            send = true;
        }
        if (send)
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    /** Mob positions received since the renderer last looked, or null if none came in. Client side. */
    @Nullable
    public ListTag takeEntityUpdate()
    {
        ListTag update = entities;
        entities = null;
        return update;
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
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

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        if (globeId >= 0)
            tag.putInt("GlobeId", globeId);
        if (snapshot != null)
        {
            tag.put("Snapshot", snapshot.toTag());
            tag.putLong("SnapshotVersion", snapshotVersion);
        }
    }

    @Override
    public CompoundTag getUpdateTag()
    {
        CompoundTag tag = saveWithoutMetadata();
        GlobeSpace.EntityFrame frame = GlobeSpace.entityFrame(globeId);
        if (frame != null)
            tag.put("Entities", frame.entities());
        return tag;
    }

    /** Sends the mob positions, plus the blocks only when those changed (they're much bigger). */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this, be -> {
            CompoundTag tag = new CompoundTag();
            if (blocksChanged)
            {
                saveAdditional(tag);
                blocksChanged = false;
            }
            GlobeSpace.EntityFrame frame = GlobeSpace.entityFrame(globeId);
            if (frame != null)
                tag.put("Entities", frame.entities());
            return tag;
        });
    }
}
