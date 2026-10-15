package com.nick.snowglobe;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Per-world record of which globes exist: their kind, size, and the latest snapshot of each. */
public class GlobeSavedData extends SavedData
{
    private static final String NAME = SnowGlobeMod.MODID + "_globes";

    /** A snapshot plus a counter that goes up each time the globe's contents change. */
    public record Entry(GlobeSnapshot snapshot, long version) {}

    private int nextId;
    private final Map<Integer, Entry> globes = new HashMap<>();
    private final Map<Integer, GlobeKind> kinds = new HashMap<>();
    private final Map<Integer, Integer> sizes = new HashMap<>();

    public static GlobeSavedData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GlobeSavedData::new, GlobeSavedData::load, null), NAME);
    }

    public int allocateId(GlobeKind kind)
    {
        kinds.put(nextId, kind);
        setDirty();
        return nextId++;
    }

    public GlobeKind kindOf(int id)
    {
        return kinds.getOrDefault(id, GlobeKind.SNOW_GLOBE);
    }

    public int sizeOf(int id)
    {
        return sizes.getOrDefault(id, GlobeSpace.DEFAULT_SIZE);
    }

    public void setSize(int id, int size)
    {
        sizes.put(id, size);
        setDirty();
    }

    @Nullable
    public Entry get(int id)
    {
        return globes.get(id);
    }

    public void storeSnapshot(int id, GlobeSnapshot snapshot)
    {
        Entry old = globes.get(id);
        if (old != null && old.snapshot().equals(snapshot))
            return;
        globes.put(id, new Entry(snapshot, old == null ? 1 : old.version() + 1));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries)
    {
        tag.putInt("NextId", nextId);
        ListTag list = new ListTag();
        globes.forEach((id, entry) -> {
            CompoundTag globe = new CompoundTag();
            globe.putInt("Id", id);
            globe.putLong("Version", entry.version());
            globe.put("Snapshot", entry.snapshot().toTag());
            list.add(globe);
        });
        tag.put("Globes", list);
        CompoundTag kindsTag = new CompoundTag();
        kinds.forEach((id, kind) -> kindsTag.putString(Integer.toString(id), kind.getName()));
        tag.put("Kinds", kindsTag);
        CompoundTag sizesTag = new CompoundTag();
        sizes.forEach((id, size) -> sizesTag.putInt(Integer.toString(id), size));
        tag.put("Sizes", sizesTag);
        return tag;
    }

    private static GlobeSavedData load(CompoundTag tag, HolderLookup.Provider registries)
    {
        GlobeSavedData data = new GlobeSavedData();
        data.nextId = tag.getInt("NextId");
        ListTag list = tag.getList("Globes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag globe = list.getCompound(i);
            GlobeSnapshot snapshot = GlobeSnapshot.fromTag(globe.getCompound("Snapshot"));
            if (snapshot != null)
                data.globes.put(globe.getInt("Id"), new Entry(snapshot, globe.getLong("Version")));
        }
        CompoundTag kindsTag = tag.getCompound("Kinds");
        CompoundTag sizesTag = tag.getCompound("Sizes");
        for (String key : kindsTag.getAllKeys())
            parseId(key, id -> data.kinds.put(id, GlobeKind.byName(kindsTag.getString(key))));
        for (String key : sizesTag.getAllKeys())
            parseId(key, id -> data.sizes.put(id, sizesTag.getInt(key)));
        return data;
    }

    private static void parseId(String key, java.util.function.IntConsumer action)
    {
        try
        {
            action.accept(Integer.parseInt(key));
        }
        catch (NumberFormatException ignored)
        {
        }
    }
}
