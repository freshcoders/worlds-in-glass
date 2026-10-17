package com.nick.snowglobe.client;

import com.nick.snowglobe.GlobeItem;
import com.nick.snowglobe.SnowGlobeMod;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only setup: the settings screen (Mods > Snow Globes > Config) and hooks the common code calls on clients. */
@Mod(value = SnowGlobeMod.MODID, dist = Dist.CLIENT)
public class SnowGlobeClient
{
    public SnowGlobeClient(ModContainer container)
    {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        GlobeItem.shiftDown = Screen::hasShiftDown;
        com.nick.snowglobe.GlobeClientHooks.wound = MusicBoxes::wound;
        com.nick.snowglobe.GlobeClientHooks.openHandbook = SnowGlobeClient::openHandbook;
    }

    /** Opens the handbook like a written book, its pages from the language file (so it can be translated). */
    private static void openHandbook()
    {
        java.util.List<net.minecraft.network.chat.Component> pages = new java.util.ArrayList<>();
        for (int i = 1; i <= com.nick.snowglobe.HandbookItem.PAGES; i++)
            pages.add(net.minecraft.network.chat.Component.translatable("snowglobe.handbook.page." + i));
        net.minecraft.client.Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.inventory.BookViewScreen(
                new net.minecraft.client.gui.screens.inventory.BookViewScreen.BookAccess(pages)));
    }
}
