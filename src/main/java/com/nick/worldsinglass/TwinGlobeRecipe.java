package com.nick.worldsinglass;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Twin globes: a globe with a world in it, an empty globe of the same kind and an ender pearl make the empty one a
 * twin. Both open onto the same world and show the same miniature, wherever each one stands. The first globe is
 * given back.
 */
public class TwinGlobeRecipe extends CustomRecipe
{
    public TwinGlobeRecipe(CraftingBookCategory category)
    {
        super(category);
    }

    /** The globe with a world in it, the empty globe, and the pearl, or null if the grid isn't exactly those. */
    private record Parts(int full, int empty) {}

    private static Parts find(CraftingInput input)
    {
        int full = -1, empty = -1, pearls = 0;
        for (int i = 0; i < input.size(); i++)
        {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty())
                continue;
            if (stack.is(Items.ENDER_PEARL))
                pearls++;
            else if (stack.getItem() instanceof GlobeItem)
            {
                CustomData data = stack.get(WorldsInGlassMod.GLOBE_DATA.get());
                boolean hasWorld = data != null && data.contains("GlobeId");
                if (hasWorld && full < 0)
                    full = i;
                else if (!hasWorld && empty < 0)
                    empty = i;
                else
                    return null;
            }
            else
                return null;
        }
        if (full < 0 || empty < 0 || pearls != 1 || input.getItem(full).getItem() != input.getItem(empty).getItem())
            return null;
        return new Parts(full, empty);
    }

    @Override
    public boolean matches(CraftingInput input, Level level)
    {
        return find(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries)
    {
        Parts parts = find(input);
        if (parts == null)
            return ItemStack.EMPTY;
        ItemStack twin = input.getItem(parts.empty()).copyWithCount(1);
        CompoundTag tag = input.getItem(parts.full()).get(WorldsInGlassMod.GLOBE_DATA.get()).copyTag();
        twin.set(WorldsInGlassMod.GLOBE_DATA.get(), CustomData.of(tag));
        return twin;
    }

    /** The globe that was copied stays in the grid. */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input)
    {
        NonNullList<ItemStack> remaining = super.getRemainingItems(input);
        Parts parts = find(input);
        if (parts != null)
            remaining.set(parts.full(), input.getItem(parts.full()).copyWithCount(1));
        return remaining;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height)
    {
        return width * height >= 3;
    }

    @Override
    public RecipeSerializer<?> getSerializer()
    {
        return WorldsInGlassMod.TWIN_GLOBE.get();
    }
}
