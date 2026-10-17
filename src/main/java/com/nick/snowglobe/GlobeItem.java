package com.nick.snowglobe;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.function.BooleanSupplier;

/** A globe as an item. Its look (with the miniature) comes from GlobeItemRenderer, hooked up in ClientSetup. */
public class GlobeItem extends BlockItem
{
    /** Whether Shift is held, for the longer tooltip. Set by the client; always false elsewhere. */
    public static BooleanSupplier shiftDown = () -> false;

    private final GlobeKind kind;

    public GlobeItem(GlobeBlock block, Properties properties)
    {
        super(block, properties);
        this.kind = block.kind();
    }

    public GlobeKind kind()
    {
        return kind;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.translatable("snowglobe.tooltip." + kind.getName()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        CustomData data = stack.get(SnowGlobeMod.GLOBE_DATA.get());
        if (data != null)
        {
            CompoundTag tag = data.copyTag();
            if (tag.contains("Snapshot"))
            {
                CompoundTag snapshot = tag.getCompound("Snapshot");
                int size = snapshot.getInt("Size") * Math.max(1, snapshot.getInt("Scale"));
                tooltip.add(Component.translatable("snowglobe.tooltip.size", size, size, size).withStyle(ChatFormatting.DARK_AQUA));
            }
        }
        if (shiftDown.getAsBoolean())
        {
            for (String line : new String[] {"shake", "enter", "turn", "knock", "more"})
                tooltip.add(Component.translatable("snowglobe.tooltip.how." + line).withStyle(ChatFormatting.DARK_GRAY));
        }
        else
            tooltip.add(Component.translatable("snowglobe.tooltip.shift").withStyle(ChatFormatting.DARK_GRAY));
    }
}
