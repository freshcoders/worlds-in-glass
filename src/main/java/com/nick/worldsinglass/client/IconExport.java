package com.nick.worldsinglass.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * For the website's recipe pictures, not part of play: started with -Dworldsinglass.exportIcons=<folder>, the game draws
 * each item named in <folder>/items.txt as it looks in an inventory slot, saves it as <folder>/<namespace>_<path>.png
 * on the slot's grey, and quits.
 */
public final class IconExport
{
    private static final int SIZE = 128;
    private static boolean done;

    private IconExport() {}

    static void registerIfAsked()
    {
        if (System.getProperty("worldsinglass.exportIcons") != null)
            NeoForge.EVENT_BUS.addListener(IconExport::onRender);
    }

    private static void onRender(ScreenEvent.Render.Post event)
    {
        if (done || !(event.getScreen() instanceof TitleScreen))
            return;
        done = true;
        Minecraft mc = Minecraft.getInstance();
        Path folder = Path.of(System.getProperty("worldsinglass.exportIcons"));
        try
        {
            List<String> ids = Files.readAllLines(folder.resolve("items.txt"));
            TextureTarget target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
            target.setClearColor(139 / 255f, 139 / 255f, 139 / 255f, 1);
            Matrix4f projection = RenderSystem.getProjectionMatrix();
            VertexSorting sorting = RenderSystem.getVertexSorting();
            Matrix4fStack view = RenderSystem.getModelViewStack();
            for (String id : ids)
            {
                if (id.isBlank())
                    continue;
                ResourceLocation key = ResourceLocation.parse(id.trim());
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(key));
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                // The GUI's own set-up, with one 16-pixel slot filling the picture.
                RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 16, 16, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
                view.pushMatrix();
                view.translation(0, 0, -11000);
                RenderSystem.applyModelViewMatrix();
                GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                graphics.renderItem(stack, 0, 0);
                graphics.flush();
                view.popMatrix();
                RenderSystem.applyModelViewMatrix();
                try (NativeImage image = new NativeImage(SIZE, SIZE, false))
                {
                    RenderSystem.bindTexture(target.getColorTextureId());
                    image.downloadTexture(0, false);
                    image.flipY();
                    image.writeToFile(folder.resolve(key.getNamespace() + "_" + key.getPath() + ".png"));
                }
            }
            target.destroyBuffers();
            RenderSystem.setProjectionMatrix(projection, sorting);
            mc.getMainRenderTarget().bindWrite(true);
            System.out.println("[IconExport] wrote " + ids.size() + " icons to " + folder);
        }
        catch (Exception e)
        {
            System.out.println("[IconExport] failed: " + e);
            e.printStackTrace();
        }
        mc.stop();
    }
}
