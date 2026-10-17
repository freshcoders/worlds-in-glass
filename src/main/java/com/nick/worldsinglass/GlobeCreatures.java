package com.nick.worldsinglass;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Getting living things in and out of globes the way you would with real ones: fish poured in from a bucket (and
 * caught again with one), animals led in on a lead. Nothing is made from nothing: the fish leave their bucket of
 * water behind, and the mobs walk in themselves.
 */
public final class GlobeCreatures
{
    private GlobeCreatures() {}

    // ---- Fish ----

    /** Lets the fish (or axolotl, or tadpole) in this bucket swim into the globe's water. The bucket keeps its water. */
    static void releaseFish(ServerPlayer player, InteractionHand hand, ItemStack stack, GlobeBlockEntity globe)
    {
        if (!(stack.getItem() instanceof MobBucketItem bucket))
            return;
        MinecraftServer server = player.server;
        ServerLevel level = server.getLevel(GlobeSpace.LEVEL);
        if (level == null || globe.globeId() < 0)
        {
            player.displayClientMessage(Component.translatable("worldsinglass.creatures.no_water"), true);
            return;
        }
        BlockPos water = findWater(server, level, globe.globeId());
        if (water == null)
        {
            player.displayClientMessage(Component.translatable("worldsinglass.creatures.no_water"), true);
            return;
        }
        bucket.checkExtraContent(player, level, stack, water);
        player.level().playSound(null, globe.getBlockPos(), SoundEvents.BUCKET_EMPTY_FISH, SoundSource.BLOCKS, 1.0f, 1.4f);
        if (!player.getAbilities().instabuild)
            player.setItemInHand(hand, new ItemStack(Items.WATER_BUCKET));
        GlobeAdvancements.award(player, "something_fishy");
        GlobeSpace.refreshSnapshot(server, globe.globeId());
    }

    /** Catches one fish (or axolotl, or tadpole) from the globe with a water bucket. False if there's none to catch. */
    static boolean catchFish(ServerPlayer player, InteractionHand hand, GlobeBlockEntity globe)
    {
        MinecraftServer server = player.server;
        ServerLevel level = server.getLevel(GlobeSpace.LEVEL);
        if (level == null || globe.globeId() < 0)
            return false;
        GlobeSavedData data = GlobeSavedData.get(server);
        List<Entity> fish = level.getEntities((Entity) null, GlobeSpace.spaceBox(GlobeSpace.interiorMin(server, globe.globeId()),
                data.sizeOf(globe.globeId())), e -> e instanceof Bucketable && e.isAlive());
        if (fish.isEmpty())
            return false;
        Entity caught = fish.get(level.random.nextInt(fish.size()));
        Bucketable bucketable = (Bucketable) caught;
        ItemStack filled = bucketable.getBucketItemStack();
        bucketable.saveToBucketTag(filled);
        caught.discard();
        player.level().playSound(null, globe.getBlockPos(), bucketable.getPickupSound(), SoundSource.BLOCKS, 1.0f, 1.3f);
        player.setItemInHand(hand, ItemUtils.createFilledResult(player.getItemInHand(hand), player, filled, false));
        return true;
    }

    /** A water source in the globe for fish to go into, under the surface if possible, or null if it holds no water. */
    @Nullable
    private static BlockPos findWater(MinecraftServer server, ServerLevel level, int id)
    {
        GlobeSavedData data = GlobeSavedData.get(server);
        GlobeKind kind = data.kindOf(id);
        int size = data.sizeOf(id);
        List<BlockPos> under = new ArrayList<>(), any = new ArrayList<>();
        GlobeSpace.forEachBlock(level, GlobeSpace.interiorMin(server, id), size, (x, y, z, state, pos) -> {
            if (any.size() >= 4000 || !kind.inside(x, y, z, size) || !state.is(Blocks.WATER) || !state.getFluidState().isSource())
                return;
            any.add(pos.immutable());
            if (level.getBlockState(pos.above()).is(Blocks.WATER))
                under.add(pos.immutable());
        });
        List<BlockPos> from = under.isEmpty() ? any : under;
        return from.isEmpty() ? null : from.get(level.random.nextInt(from.size()));
    }

    // ---- Animals on a lead ----

    /** The mobs a player is leading, close enough to come along through a globe's glass with them. */
    static List<Mob> leashedTo(ServerPlayer player)
    {
        return player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(10), mob -> mob.getLeashHolder() == player);
    }

    /** Brings led mobs to where the player just went, still on their leads. True if any came along. */
    static boolean bringAlong(List<Mob> mobs, ServerPlayer player)
    {
        boolean any = false;
        ServerLevel target = player.serverLevel();
        Vec3 pos = player.position();
        for (Mob mob : mobs)
        {
            if (!mob.isAlive())
                continue;
            Entity moved;
            if (mob.level() == target)
            {
                mob.teleportTo(pos.x, pos.y, pos.z);
                moved = mob;
            }
            else
                moved = mob.changeDimension(new DimensionTransition(target, pos, Vec3.ZERO, mob.getYRot(), mob.getXRot(), DimensionTransition.DO_NOTHING));
            if (moved instanceof Mob arrived)
            {
                arrived.setLeashedTo(player, true);
                any = true;
            }
        }
        return any;
    }
}
