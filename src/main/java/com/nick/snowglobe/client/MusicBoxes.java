package com.nick.snowglobe.client;

import com.nick.snowglobe.GlobeKind;
import com.nick.snowglobe.SnowGlobeConfig;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Music boxes playing on this client. Winding one plays its globe's tune once, slowing down at the end as it
 * runs out; anyone inside hears it too, huge and deep, all around them.
 */
@EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT)
public final class MusicBoxes
{
    /**
     * Each kind's tune, as notes "midi:ticks". Old tunes, long out of copyright, and a couple made up for the
     * occasion. Note blocks reach two octaves, F#3 (54) to F#5 (78).
     */
    private static final Map<GlobeKind, int[][]> TUNES = new EnumMap<>(GlobeKind.class);

    static
    {
        // Jingle Bells (1857).
        TUNES.put(GlobeKind.SNOW_GLOBE, tune("64:4 64:4 64:8 64:4 64:4 64:8 64:4 67:4 60:6 62:2 64:12 "
                + "65:4 65:4 65:6 65:2 65:4 64:4 64:4 64:2 64:2 64:4 62:4 62:4 64:4 62:8 67:12"));
        // Twinkle, Twinkle, Little Star (1806).
        TUNES.put(GlobeKind.STAR_GLOBE, tune("60:5 60:5 67:5 67:5 69:5 69:5 67:10 65:5 65:5 64:5 64:5 62:5 62:5 60:10 "
                + "67:5 67:5 65:5 65:5 64:5 64:5 62:10"));
        // In the Hall of the Mountain King (Grieg, 1875).
        TUNES.put(GlobeKind.STORM_GLOBE, tune("57:3 59:3 60:3 62:3 64:3 60:3 64:6 63:3 59:3 63:6 62:3 58:3 62:6 "
                + "57:3 59:3 60:3 62:3 64:3 60:3 64:3 69:3 67:3 64:3 60:3 64:3 67:12"));
        // Morning Mood (Grieg, 1875).
        TUNES.put(GlobeKind.TERRARIUM, tune("67:5 64:5 62:5 60:5 62:5 64:5 67:5 64:5 62:5 60:5 62:4 64:3 62:3 64:3 "
                + "67:5 64:5 67:5 69:5 64:5 69:5 67:5 64:5 62:5 60:14"));
        // What Shall We Do with the Drunken Sailor (sea shanty).
        TUNES.put(GlobeKind.SHIP_BOTTLE, tune("69:4 69:2 69:2 69:4 69:2 69:2 69:4 62:4 65:4 69:4 67:4 67:2 67:2 67:4 67:2 67:2 "
                + "67:4 60:4 64:4 67:4 69:4 69:2 69:2 69:4 69:2 69:2 69:4 71:4 72:4 71:4 69:4 67:4 64:4 62:8 64:4 60:4 62:8"));
        // A made-up tune in the Phrygian dominant scale, for the dunes.
        TUNES.put(GlobeKind.DESERT_GLOBE, tune("62:4 63:4 66:4 67:4 69:8 67:4 66:4 63:8 62:4 63:4 66:4 70:4 69:8 67:4 66:4 "
                + "67:4 66:4 63:4 62:12"));
        // A made-up rippling tune for the fish.
        TUNES.put(GlobeKind.AQUARIUM, tune("65:3 69:3 72:3 77:3 72:3 69:3 67:3 71:3 74:3 71:3 67:3 62:3 64:3 67:3 72:3 76:3 "
                + "72:3 67:3 65:3 69:3 72:3 69:3 65:6 60:12"));
        // Dies Irae (13th century chant).
        TUNES.put(GlobeKind.NETHER_JAR, tune("65:6 64:6 65:6 62:6 64:6 60:6 62:12 65:6 65:6 67:6 65:6 64:6 62:6 60:6 64:6 "
                + "65:6 64:6 62:12"));
    }

    private static int[][] tune(String notes)
    {
        String[] parts = notes.trim().split("\\s+");
        int[][] out = new int[parts.length][];
        for (int i = 0; i < parts.length; i++)
        {
            String[] p = parts[i].split(":");
            out[i] = new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        }
        return out;
    }

    /** A tune playing: when each note starts (ticks after winding, slowing towards the end) and how far it got. */
    private static final class Playing
    {
        final GlobeKind kind;
        final int[] at;
        final long start;
        int next;

        Playing(GlobeKind kind, long start)
        {
            this.kind = kind;
            this.start = start;
            int[][] notes = TUNES.get(kind);
            at = new int[notes.length];
            float t = 0;
            for (int i = 0; i < notes.length; i++)
            {
                at[i] = Math.round(t);
                // Running down: the last notes come more and more slowly.
                float progress = (float) i / notes.length;
                t += notes[i][1] * (1 + 0.9f * progress * progress * progress);
            }
        }

        boolean done(long now)
        {
            return next >= at.length && now - start > at[at.length - 1] + 20;
        }
    }

    private static long ticks;
    private static final Map<BlockPos, Playing> outside = new HashMap<>();
    private static Playing inside;

    private MusicBoxes() {}

    /** A music box was wound: play its tune from the globe. */
    public static void wound(BlockPos pos, GlobeKind kind)
    {
        if (SnowGlobeConfig.musicBoxes())
            outside.put(pos.immutable(), new Playing(kind, ticks));
    }

    /** The music box of the globe you're inside was wound: play its tune around you, huge. */
    static void woundInside(GlobeKind kind)
    {
        if (SnowGlobeConfig.musicBoxes())
            inside = new Playing(kind, ticks);
    }

    /** How far the key of the music box at this spot has turned, in degrees: it spins while the tune plays. */
    static float keyAngle(BlockPos pos, float partial)
    {
        Playing playing = outside.get(pos);
        if (playing == null)
            return 0;
        float elapsed = ticks - playing.start + (partial - (float) Math.floor(partial));
        float length = playing.at[playing.at.length - 1] + 20;
        float progress = Math.min(1, elapsed / length);
        // Fast at first, slowing as it unwinds.
        return 720 * (1 - (1 - progress) * (1 - progress));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused())
            return;
        ticks++;
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null)
        {
            outside.clear();
            inside = null;
            return;
        }
        for (Iterator<Map.Entry<BlockPos, Playing>> it = outside.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<BlockPos, Playing> entry = it.next();
            Playing playing = entry.getValue();
            BlockPos pos = entry.getKey();
            playDue(playing, (midi, last) -> level.playLocalSound(pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.RECORDS,
                    last ? 0.45f : 0.6f, pitch(midi), false));
            if (playing.done(ticks))
                it.remove();
        }
        if (inside != null)
        {
            // Inside, the same notes as a deep bell ringing all around.
            playDue(inside, (midi, last) -> {
                level.playLocalSound(player.getX(), player.getY() + 4, player.getZ(), SoundEvents.NOTE_BLOCK_BELL.value(),
                        SoundSource.RECORDS, 1.4f, pitch(midi) * 0.5f, false);
                level.playLocalSound(player.getX(), player.getY() + 4, player.getZ(), SoundEvents.NOTE_BLOCK_HARP.value(),
                        SoundSource.RECORDS, 0.8f, pitch(midi - 12), false);
            });
            if (inside.done(ticks))
                inside = null;
        }
    }

    private interface Note
    {
        void play(int midi, boolean last);
    }

    private static void playDue(Playing playing, Note note)
    {
        int[][] notes = TUNES.get(playing.kind);
        while (playing.next < playing.at.length && ticks - playing.start >= playing.at[playing.next])
        {
            note.play(notes[playing.next][0], playing.next == notes.length - 1);
            playing.next++;
        }
    }

    /** A note block's pitch for a midi note: F#4 (66) plays at normal speed. */
    private static float pitch(int midi)
    {
        return (float) Math.pow(2, (Math.max(54, Math.min(78, midi)) - 66) / 12.0);
    }
}
