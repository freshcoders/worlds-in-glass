package com.nick.worldsinglass;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Per-world record of which globes exist: their kind, size, and the latest snapshot of each. */
public class GlobeSavedData extends SavedData
{
    private static final String NAME = WorldsInGlassMod.MODID + "_globes";

    /** A snapshot plus a counter that goes up each time the globe's contents change. */
    public record Entry(GlobeSnapshot snapshot, long version) {}

    /** Globes made before slots existed sit this far apart, at x = id * spacing; they hold up to 62 blocks. */
    public static final int LEGACY_SPACING = 64;
    /** Width of a slot along x: room for the largest globe plus its walls, with a gap to the next. */
    public static final int SLOT_WIDTH = 320;

    private int nextId;
    private final Map<Integer, Entry> globes = new HashMap<>();
    private final Map<Integer, GlobeKind> kinds = new HashMap<>();
    private final Map<Integer, Integer> sizes = new HashMap<>();
    /** The snow globes' seasons. */
    private final Map<Integer, Integer> seasons = new HashMap<>();
    /** Snow that settled in a globe after a shake: where it lies, and the game time it melts. */
    private final Map<Integer, Map<Long, Long>> settledSnow = new HashMap<>();
    /** Where each globe's slot starts along x (its wall), for globes with a slot of their own. */
    private final Map<Integer, Integer> slots = new HashMap<>();
    private final TreeMap<Integer, Integer> slotOwners = new TreeMap<>();
    /** Start of the next free slot; slots begin after the old, closely spaced globes. -1 until the first. */
    private int nextSlotX = -1;

    public static GlobeSavedData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GlobeSavedData::new, GlobeSavedData::load, null), NAME);
    }

    public int allocateId(GlobeKind kind)
    {
        int id = nextId++;
        kinds.put(id, kind);
        giveSlot(id);
        return id;
    }

    public static final int PHASE_DAY = 0, PHASE_SUNSET = 1, PHASE_NIGHT = 2, PHASE_SUNRISE = 3;
    public static final int WINTER = 0, SPRING = 1, SUMMER = 2, AUTUMN = 3;

    /** Time of day inside, which belongs to the kind: sunset over the desert, night in the star globe, day elsewhere. */
    public int phaseOf(int id)
    {
        return switch (kindOf(id))
        {
            case DESERT_GLOBE -> PHASE_SUNSET;
            case STAR_GLOBE -> PHASE_NIGHT;
            default -> PHASE_DAY;
        };
    }

    /** A snow globe's season: winter, spring, summer or autumn (0 to 3). Shaking it moves it on. */
    public int seasonOf(int id)
    {
        return seasons.getOrDefault(id, WINTER);
    }

    public void setSeason(int id, int season)
    {
        seasons.put(id, Math.floorMod(season, 4));
        setDirty();
    }

    /** Settled snow in a globe, by position, with when it melts. Call {@link #setDirty()} after changing it. */
    public Map<Long, Long> settledSnow(int id)
    {
        return settledSnow.computeIfAbsent(id, k -> new HashMap<>());
    }

    /** The globes with settled snow, to melt it as time goes by. */
    public java.util.Set<Integer> globesWithSnow()
    {
        settledSnow.values().removeIf(Map::isEmpty);
        return settledSnow.keySet();
    }

    /** Gives a globe a slot of its own (room to grow to the largest size), after all slots in use. */
    public void giveSlot(int id)
    {
        if (nextSlotX < 0)
            nextSlotX = Math.max(nextId, 1) * LEGACY_SPACING;
        Integer old = slots.remove(id);
        if (old != null)
            slotOwners.remove(old);
        slots.put(id, nextSlotX);
        slotOwners.put(nextSlotX, id);
        nextSlotX += SLOT_WIDTH;
        setDirty();
    }

    /** Where a globe's slot starts along x (the wall on its low side). */
    public int slotStart(int id)
    {
        Integer start = slots.get(id);
        return start != null ? start : id * LEGACY_SPACING;
    }

    /** The largest build space that fits in a globe's slot. */
    public int capacity(int id)
    {
        return (slots.containsKey(id) ? SLOT_WIDTH : LEGACY_SPACING) - 2;
    }

    /** The globe whose slot holds x, or -1. */
    public int idAt(int x)
    {
        Map.Entry<Integer, Integer> slot = slotOwners.floorEntry(x);
        if (slot != null && x < slot.getKey() + SLOT_WIDTH)
            return slot.getValue();
        int legacy = Math.floorDiv(x, LEGACY_SPACING);
        boolean beforeSlots = nextSlotX < 0 || slotOwners.isEmpty() || x < slotOwners.firstKey();
        return legacy >= 0 && legacy < nextId && beforeSlots && !slots.containsKey(legacy) ? legacy : -1;
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
        CompoundTag slotsTag = new CompoundTag();
        slots.forEach((id, start) -> slotsTag.putInt(Integer.toString(id), start));
        tag.put("Slots", slotsTag);
        tag.putInt("NextSlotX", nextSlotX);
        CompoundTag seasonsTag = new CompoundTag();
        seasons.forEach((id, season) -> seasonsTag.putInt(Integer.toString(id), season));
        tag.put("Seasons", seasonsTag);
        CompoundTag snowTag = new CompoundTag();
        settledSnow.forEach((id, snow) -> {
            if (snow.isEmpty())
                return;
            long[] flat = new long[snow.size() * 2];
            int i = 0;
            for (Map.Entry<Long, Long> e : snow.entrySet())
            {
                flat[i++] = e.getKey();
                flat[i++] = e.getValue();
            }
            snowTag.putLongArray(Integer.toString(id), flat);
        });
        tag.put("SettledSnow", snowTag);
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
        CompoundTag slotsTag = tag.getCompound("Slots");
        for (String key : slotsTag.getAllKeys())
            parseId(key, id -> {
                data.slots.put(id, slotsTag.getInt(key));
                data.slotOwners.put(slotsTag.getInt(key), id);
            });
        data.nextSlotX = tag.contains("NextSlotX") ? tag.getInt("NextSlotX") : -1;
        CompoundTag seasonsTag = tag.getCompound("Seasons");
        for (String key : seasonsTag.getAllKeys())
            parseId(key, id -> data.seasons.put(id, seasonsTag.getInt(key)));
        CompoundTag snowTag = tag.getCompound("SettledSnow");
        for (String key : snowTag.getAllKeys())
            parseId(key, id -> {
                long[] flat = snowTag.getLongArray(key);
                Map<Long, Long> snow = data.settledSnow(id);
                for (int i = 0; i + 1 < flat.length; i += 2)
                    snow.put(flat[i], flat[i + 1]);
            });
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
