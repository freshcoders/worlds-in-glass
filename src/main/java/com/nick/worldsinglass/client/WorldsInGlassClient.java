package com.nick.worldsinglass.client;

import com.nick.worldsinglass.GlobeItem;
import com.nick.worldsinglass.WorldsInGlassMod;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only setup: the settings screen (Mods > Worlds in Glass > Config) and hooks the common code calls on clients. */
@Mod(value = WorldsInGlassMod.MODID, dist = Dist.CLIENT)
public class WorldsInGlassClient
{
    public WorldsInGlassClient(ModContainer container)
    {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        GlobeItem.shiftDown = Screen::hasShiftDown;
        com.nick.worldsinglass.GlobeClientHooks.openHandbook = WorldsInGlassClient::openHandbook;
        IconExport.registerIfAsked();
    }

    /** Opens the handbook like a written book, its pages from the language file (so it can be translated). */
    private static void openHandbook()
    {
        java.util.List<net.minecraft.network.chat.Component> pages = new java.util.ArrayList<>();
        for (int i = 1; i <= com.nick.worldsinglass.HandbookItem.PAGES; i++)
            pages.add(net.minecraft.network.chat.Component.translatable("worldsinglass.handbook.page." + i));
        net.minecraft.client.Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.inventory.BookViewScreen(
                new net.minecraft.client.gui.screens.inventory.BookViewScreen.BookAccess(pages)));
    }
}
