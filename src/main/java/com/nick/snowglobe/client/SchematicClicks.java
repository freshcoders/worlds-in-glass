package com.nick.snowglobe.client;

import com.nick.snowglobe.GlobeBlock;
import com.nick.snowglobe.GlobeImport;
import com.nick.snowglobe.SnowGlobeMod;
import com.nick.snowglobe.net.GlobeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Right-clicking a globe with a Create schematic imports it. Create keeps right-clicks with a schematic to
 * itself (to place its preview), so they're caught here first when aimed at a globe, and sent to the server.
 */
@EventBusSubscriber(modid = SnowGlobeMod.MODID, value = Dist.CLIENT)
public final class SchematicClicks
{
    private SchematicClicks() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isUseItem() || mc.player == null || mc.level == null
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != BlockHitResult.Type.BLOCK
                || !(mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof GlobeBlock)
                || !GlobeImport.isCreateSchematic(mc.player.getItemInHand(event.getHand())))
            return;
        PacketDistributor.sendToServer(new GlobeNetwork.ImportHeld(hit.getBlockPos()));
        event.setSwingHand(true);
        event.setCanceled(true);
    }
}
