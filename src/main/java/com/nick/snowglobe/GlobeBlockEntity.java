package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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

    public GlobeBlockEntity(BlockPos pos, BlockState state)
    {
        super(SnowGlobeMod.GLOBE_BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    public GlobeSnapshot getSnapshot()
    {
        return snapshot;
    }

    /** Gives this globe its own build space if it has none yet, and returns the globe id. */
    public int ensureGlobe(MinecraftServer server)
    {
        if (globeId < 0)
        {
            globeId = GlobeSpace.create(server);
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
        // Check about once a second, spread out over ticks so many globes don't all update at once.
        if (globe.globeId < 0 || level.getServer() == null || Math.floorMod(level.getGameTime() + pos.asLong(), 20) != 0)
            return;
        if (globe.pullSnapshot(level.getServer()))
        {
            globe.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        globeId = tag.contains("GlobeId") ? tag.getInt("GlobeId") : -1;
        snapshot = tag.contains("Snapshot") ? GlobeSnapshot.fromTag(tag.getCompound("Snapshot")) : null;
        snapshotVersion = tag.getLong("SnapshotVersion");
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
        return saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
