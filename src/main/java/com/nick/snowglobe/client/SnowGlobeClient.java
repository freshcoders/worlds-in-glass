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
    }
}
