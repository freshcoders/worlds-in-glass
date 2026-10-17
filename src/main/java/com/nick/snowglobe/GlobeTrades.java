package com.nick.snowglobe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.neoforge.event.village.WandererTradesEvent;

/**
 * Wandering traders sometimes carry a globe with a little world already in it: the kind's starter scene, built when
 * it's first placed (the same scenes creative globes come with). Chests can hold them too, see the loot modifier.
 */
public final class GlobeTrades
{
    private GlobeTrades() {}

    /** A globe of this kind that builds its starter scene when placed. */
    public static ItemStack sceneGlobe(GlobeKind kind)
    {
        ItemStack stack = new ItemStack(SnowGlobeMod.GLOBE_ITEMS.get(kind).get());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Scene", true);
        stack.set(SnowGlobeMod.GLOBE_DATA.get(), CustomData.of(tag));
        return stack;
    }

    static void onWandererTrades(WandererTradesEvent event)
    {
        event.getRareTrades().add((trader, random) -> {
            GlobeKind kind = GlobeKind.values()[random.nextInt(GlobeKind.values().length)];
            return new MerchantOffer(new ItemCost(Items.EMERALD, 10 + random.nextInt(8)), sceneGlobe(kind), 1, 5, 0.05f);
        });
    }
}
