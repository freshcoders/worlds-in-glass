package com.nick.snowglobe.client;

/** What this client remembers about one placed globe between frames. */
final class ClientGlobeState
{
    final MiniEntities mobs = new MiniEntities();
    /** The last lightning strike a thunderclap was played for, so each strike sounds once. */
    long lastThunder = Long.MIN_VALUE;
}
