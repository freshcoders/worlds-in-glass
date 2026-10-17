package com.nick.snowglobe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Dying inside a globe: you come round on the floor beside the globe you first went into (the outermost one, if you
 * were in a globe inside a globe), not back at your bed or the world's spawn.
 */
public final class GlobeWakeUp
{
    private static final String WAKE_KEY = SnowGlobeMod.MODID + ":wake_up";

    private GlobeWakeUp() {}

    /** On death in the globe dimension: remember the outermost globe to wake up beside, and forget the way back in. */
    static void onDeath(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().dimension() != GlobeSpace.LEVEL)
            return;
        CompoundTag data = player.getPersistentData();
        ListTag outer = data.getList(GlobeSpace.OUTER_KEY, Tag.TAG_COMPOUND);
        CompoundTag first = !outer.isEmpty() ? outer.getCompound(0) : data.getCompound(GlobeView.GLOBE_KEY);
        if (first.contains("Pos"))
            data.put(WAKE_KEY, first.copy());
        GlobeSpace.forgetWayBack(player);
    }

    /** The player after respawning is a new one: carry the note over. */
    static void onClone(PlayerEvent.Clone event)
    {
        CompoundTag old = event.getOriginal().getPersistentData();
        if (event.isWasDeath() && old.contains(WAKE_KEY))
            event.getEntity().getPersistentData().put(WAKE_KEY, old.getCompound(WAKE_KEY).copy());
    }

    static void onRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isEndConquered())
            return;
        CompoundTag wake = player.getPersistentData().getCompound(WAKE_KEY);
        player.getPersistentData().remove(WAKE_KEY);
        if (!wake.contains("Pos"))
            return;
        ResourceLocation dim = ResourceLocation.tryParse(wake.getString("Dim"));
        ServerLevel level = dim == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (level == null)
            return;
        BlockPos globe = BlockPos.of(wake.getLong("Pos"));
        BlockPos spot = besideGlobe(level, globe);
        if (spot == null)
            return;
        player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(), player.getXRot());
        BlockState state = level.getBlockState(globe);
        Component name = state.getBlock() instanceof GlobeBlock ? state.getBlock().getName() : Component.translatable("snowglobe.message.the_globe");
        player.displayClientMessage(Component.translatable("snowglobe.message.woke_up", name), false);
    }

    /** A spot to stand on next to the globe: floor underneath, two blocks of air. Null if there's none close by. */
    @Nullable
    private static BlockPos besideGlobe(ServerLevel level, BlockPos globe)
    {
        for (int ring = 1; ring <= 4; ring++)
            for (int dy : new int[] {0, -1, 1, -2, 2})
                for (Direction side : Direction.Plane.HORIZONTAL)
                    for (int along = -ring; along <= ring; along++)
                    {
                        BlockPos feet = globe.relative(side, ring).relative(side.getClockWise(), along).above(dy);
                        if (level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty())
                            return feet;
                    }
        return null;
    }
}
