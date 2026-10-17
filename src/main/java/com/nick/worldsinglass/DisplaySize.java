package com.nick.worldsinglass;

import net.minecraft.util.StringRepresentable;

/** How big a globe looks in the world: grows with the size of its build space. */
public enum DisplaySize implements StringRepresentable
{
    /** One block: build spaces up to 32. */
    SMALL("s", 1),
    /** Two blocks: up to 96. */
    MEDIUM("m", 2),
    /** Three blocks: bigger still. */
    LARGE("l", 3);

    private final String name;
    /** How many times bigger than one block it's drawn. */
    public final int scale;

    DisplaySize(String name, int scale)
    {
        this.name = name;
        this.scale = scale;
    }

    public static DisplaySize forGlobeSize(int size)
    {
        return size <= 32 ? SMALL : size <= 96 ? MEDIUM : LARGE;
    }

    @Override
    public String getSerializedName()
    {
        return name;
    }
}
