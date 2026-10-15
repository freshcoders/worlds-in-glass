package com.nick.snowglobe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Per-world record of which globe ids exist and the latest snapshot of each. */
public class GlobeSavedData extends SavedData
{
    private static final String NAME = SnowGlobeMod.MODID + "_globes";

    /** A snapshot plus a counter that goes up each time the globe's contents change. */
    public record Entry(GlobeSnapshot snapshot, long version) {}

    private int nextId;
    private final Map<Integer, Entry> globes = new HashMap<>();
    private final Map<Integer, GlobeKind> kinds = new HashMap<>();

    public static GlobeSavedData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(GlobeSavedData::load, GlobeSavedData::new, NAME);
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
    public CompoundTag save(CompoundTag tag)
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
        return tag;
    }

    private static GlobeSavedData load(CompoundTag tag)
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
        for (String key : kindsTag.getAllKeys())
        {
            try
            {
                data.kinds.put(Integer.parseInt(key), GlobeKind.byName(kindsTag.getString(key)));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return data;
    }
}
