package com.nick.snowglobe.client;

import com.nick.snowglobe.net.GlobeNetwork;

/** Where globe messages from the server arrive on this client (on the game thread). */
public final class ClientNetwork
{
    private ClientNetwork() {}

    public static void onOutsideView(GlobeNetwork.OutsideView payload)
    {
        OutsideView.receive(payload.data());
    }

    public static void onOutsidePlayers(GlobeNetwork.OutsidePlayers payload)
    {
        OutsideView.receivePlayers(payload.data());
    }

    public static void onGlobeEvent(GlobeNetwork.GlobeEvent payload)
    {
        GlobeEffects.onEvent(payload.event(), payload.kind());
    }
}
