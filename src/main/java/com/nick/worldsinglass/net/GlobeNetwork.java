package com.nick.worldsinglass.net;

import com.nick.worldsinglass.GlobeImport;
import com.nick.worldsinglass.WorldsInGlassMod;
import net.minecraft.core.BlockPos;
import com.nick.worldsinglass.client.ClientNetwork;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Messages from the server to players about globes they're in or near. */
public final class GlobeNetwork
{
    private GlobeNetwork() {}

    /** The world around the globe a player is inside, to draw giant around them. */
    public record OutsideView(CompoundTag data) implements CustomPacketPayload
    {
        public static final Type<OutsideView> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WorldsInGlassMod.MODID, "outside_view"));
        public static final StreamCodec<ByteBuf, OutsideView> CODEC = StreamCodec.composite(
                ByteBufCodecs.TRUSTED_COMPOUND_TAG, OutsideView::data, OutsideView::new);

        @Override
        public Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    /** Where the players near that globe are right now (so they show up as giants peering in). */
    public record OutsidePlayers(CompoundTag data) implements CustomPacketPayload
    {
        public static final Type<OutsidePlayers> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WorldsInGlassMod.MODID, "outside_players"));
        public static final StreamCodec<ByteBuf, OutsidePlayers> CODEC = StreamCodec.composite(
                ByteBufCodecs.TRUSTED_COMPOUND_TAG, OutsidePlayers::data, OutsidePlayers::new);

        @Override
        public Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    /** Something that happened to a player's globe; {@code kind} is the globe kind's ordinal. */
    public record GlobeEvent(int event, int kind) implements CustomPacketPayload
    {
        /** The globe you're in was shaken from outside. */
        public static final int SHAKEN = 0;
        /** Someone knocked on the glass of the globe you're in. */
        public static final int KNOCKED = 1;
        /** You're about to go into a globe: zoom in. */
        public static final int DIVE_IN = 2;
        /** You're about to leave a globe: zoom out. */
        public static final int DIVE_OUT = 3;
        /** You've arrived (inside or back outside): open up again. */
        public static final int ARRIVED = 4;

        public static final Type<GlobeEvent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WorldsInGlassMod.MODID, "globe_event"));
        public static final StreamCodec<ByteBuf, GlobeEvent> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GlobeEvent::event, ByteBufCodecs.VAR_INT, GlobeEvent::kind, GlobeEvent::new);

        @Override
        public Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    /**
     * From a player: import the schematic in my hand into the globe at this spot. (Create keeps right-clicks
     * with a schematic to itself, so the globe never hears about them otherwise.)
     */
    public record ImportHeld(BlockPos globePos) implements CustomPacketPayload
    {
        public static final Type<ImportHeld> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WorldsInGlassMod.MODID, "import_held"));
        public static final StreamCodec<ByteBuf, ImportHeld> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ImportHeld::globePos, ImportHeld::new);

        @Override
        public Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event)
    {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ImportHeld.TYPE, ImportHeld.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player)
                GlobeImport.importHeldAt(player, payload.globePos());
        });
        // The client side lives in ClientNetwork; the lambdas only touch it when a message arrives on a client.
        registrar.playToClient(OutsideView.TYPE, OutsideView.CODEC, (payload, context) -> ClientNetwork.onOutsideView(payload));
        registrar.playToClient(OutsidePlayers.TYPE, OutsidePlayers.CODEC, (payload, context) -> ClientNetwork.onOutsidePlayers(payload));
        registrar.playToClient(GlobeEvent.TYPE, GlobeEvent.CODEC, (payload, context) -> ClientNetwork.onGlobeEvent(payload));
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload)
    {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
