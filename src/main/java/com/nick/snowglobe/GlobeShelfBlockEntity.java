package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The two globes on a shelf. Their miniatures follow what's built inside them, a couple of times a minute. */
public class GlobeShelfBlockEntity extends BlockEntity
{
    private final NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);

    public GlobeShelfBlockEntity(BlockPos pos, BlockState state)
    {
        super(SnowGlobeMod.SHELF_BLOCK_ENTITY.get(), pos, state);
    }

    public ItemStack get(int slot)
    {
        return items.get(slot);
    }

    public void set(int slot, ItemStack stack)
    {
        items.set(slot, stack);
        changed();
    }

    NonNullList<ItemStack> items()
    {
        return items;
    }

    private void changed()
    {
        setChanged();
        if (level != null)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GlobeShelfBlockEntity shelf)
    {
        MinecraftServer server = level.getServer();
        if (server == null || Math.floorMod(level.getGameTime() + pos.asLong(), 40) != 0)
            return;
        boolean changed = false;
        for (ItemStack stack : shelf.items)
            changed |= refresh(server, stack);
        if (changed)
            shelf.changed();
    }

    /** Brings a globe item's saved miniature (and time of day and season) up to date with its globe. */
    private static boolean refresh(MinecraftServer server, ItemStack stack)
    {
        CustomData data = stack.get(SnowGlobeMod.GLOBE_DATA.get());
        if (data == null || !data.contains("GlobeId"))
            return false;
        CompoundTag tag = data.copyTag();
        int id = tag.getInt("GlobeId");
        GlobeSavedData saved = GlobeSavedData.get(server);
        GlobeSavedData.Entry entry = saved.get(id);
        if (entry == null)
            return false;
        boolean changed = entry.version() != tag.getLong("SnapshotVersion") || saved.phaseOf(id) != tag.getInt("Phase")
                || saved.seasonOf(id) != tag.getInt("Season");
        if (!changed)
            return false;
        tag.put("Snapshot", entry.snapshot().toTag());
        tag.putLong("SnapshotVersion", entry.version());
        tag.putInt("Phase", saved.phaseOf(id));
        tag.putInt("Season", saved.seasonOf(id));
        stack.set(SnowGlobeMod.GLOBE_DATA.get(), CustomData.of(tag));
        return true;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries)
    {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries)
    {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, true, registries);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries)
    {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, items, true, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
