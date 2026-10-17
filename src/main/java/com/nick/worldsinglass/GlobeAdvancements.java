package com.nick.worldsinglass;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The advancement trail: most steps are things done with globes (shaking, winding, going in), which no vanilla
 * trigger sees, so they have a single "done" criterion that the mod awards here.
 */
public final class GlobeAdvancements
{
    private GlobeAdvancements() {}

    /** Awards the advancement {@code worldsinglass:<name>}, if this player hasn't got it yet. */
    public static void award(ServerPlayer player, String name)
    {
        AdvancementHolder advancement = player.server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(WorldsInGlassMod.MODID, name));
        if (advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone())
            player.getAdvancements().award(advancement, "done");
    }
}
