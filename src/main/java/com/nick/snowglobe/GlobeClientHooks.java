package com.nick.snowglobe;

/**
 * What common code asks of the client, set by the client when it starts. On a dedicated server these stay no-ops,
 * so client-only classes are never touched there.
 */
public final class GlobeClientHooks
{
    /** Open the globe keeper's handbook. */
    public static Runnable openHandbook = () -> {};

    private GlobeClientHooks() {}
}
