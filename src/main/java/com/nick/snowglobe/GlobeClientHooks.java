package com.nick.snowglobe;

import net.minecraft.core.BlockPos;

import java.util.function.BiConsumer;

/**
 * What common code asks of the client, set by the client when it starts. On a dedicated server these stay no-ops,
 * so client-only classes are never touched there.
 */
public final class GlobeClientHooks
{
    /** A music box was wound at this spot: play its tune. */
    public static BiConsumer<BlockPos, GlobeKind> wound = (pos, kind) -> {};
    /** Open the globe keeper's handbook. */
    public static Runnable openHandbook = () -> {};

    private GlobeClientHooks() {}
}
