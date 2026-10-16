package com.nick.snowglobe.client;

import com.nick.snowglobe.GlobeBlock;
import com.nick.snowglobe.GlobeImport;
import com.nick.snowglobe.SnowGlobeMod;
import com.nick.snowglobe.net.GlobeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
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

    /** Create grabs the raw mouse button (before the use key is even looked at), so get in before it there. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.getAction() != GLFW.GLFW_PRESS || mc.screen != null)
            return;
        InteractionHand hand = schematicHandAimedAtGlobe();
        BlockPos globe = mc.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : null;
        if (hand == null || globe == null || mc.player == null)
            return;
        PacketDistributor.sendToServer(new GlobeNetwork.ImportHeld(globe));
        mc.player.swing(hand);
        event.setCanceled(true);
    }

    /** In case the use key is bound to something other than the mouse. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isUseItem() || schematicHandAimedAtGlobe() != event.getHand() || !(mc.hitResult instanceof BlockHitResult hit))
            return;
        PacketDistributor.sendToServer(new GlobeNetwork.ImportHeld(hit.getBlockPos()));
        event.setSwingHand(true);
        event.setCanceled(true);
    }

    /** The hand holding a Create schematic, if the player is aiming at a globe; otherwise null. */
    @Nullable
    private static InteractionHand schematicHandAimedAtGlobe()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != BlockHitResult.Type.BLOCK
                || !(mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof GlobeBlock))
            return null;
        for (InteractionHand hand : InteractionHand.values())
            if (GlobeImport.isCreateSchematic(mc.player.getItemInHand(hand)))
                return hand;
        return null;
    }
}
