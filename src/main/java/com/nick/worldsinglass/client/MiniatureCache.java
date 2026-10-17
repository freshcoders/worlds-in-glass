package com.nick.worldsinglass.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.worldsinglass.GlobeSnapshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What this client builds from one snapshot: its block entities, and the miniature baked into a mesh (one per
 * light level it's shown at). Kept with the snapshot; freed when the snapshot is replaced or forgotten.
 */
final class MiniatureCache implements AutoCloseable
{
    /** Caches whose snapshot may still be in use; checked now and then so forgotten ones free their meshes. */
    private static final List<Live> LIVE = new ArrayList<>();
    private static int sinceSweep;

    private record Live(WeakReference<GlobeSnapshot> snapshot, MiniatureCache cache) {}

    final MiniBlockEntities blockEntities;
    private final Map<Integer, BakedMesh> meshes = new LinkedHashMap<>();

    private MiniatureCache(MiniBlockEntities blockEntities)
    {
        this.blockEntities = blockEntities;
    }

    static MiniatureCache of(GlobeSnapshot snapshot, ClientLevel level)
    {
        sweep();
        if (snapshot.clientCache instanceof MiniatureCache cached)
            return cached;
        MiniatureCache created = new MiniatureCache(new MiniBlockEntities(snapshot, level));
        snapshot.clientCache = created;
        LIVE.add(new Live(new WeakReference<>(snapshot), created));
        return created;
    }

    /** Frees a snapshot's cache straight away (when a globe gets a newer snapshot). */
    static void release(GlobeSnapshot snapshot)
    {
        if (snapshot.clientCache instanceof MiniatureCache cache)
        {
            cache.close();
            snapshot.clientCache = null;
        }
    }

    /** Every so often: free the meshes of snapshots nobody holds any more. */
    private static void sweep()
    {
        if (++sinceSweep < 200)
            return;
        sinceSweep = 0;
        for (Iterator<Live> it = LIVE.iterator(); it.hasNext(); )
        {
            Live live = it.next();
            GlobeSnapshot snapshot = live.snapshot().get();
            if (snapshot == null || snapshot.clientCache != live.cache())
            {
                live.cache().close();
                it.remove();
            }
        }
    }

    /** The snapshot's blocks and water as a mesh, lit with {@code light}; built the first time it's asked for. */
    BakedMesh mesh(GlobeSnapshot snapshot, int light, ClientLevel level, BlockPos where, BlockRenderDispatcher blocks)
    {
        BakedMesh mesh = meshes.get(light);
        if (mesh == null)
        {
            // A globe moved into other light gets a new mesh; keep only the few most recent.
            if (meshes.size() >= 4)
            {
                Iterator<BakedMesh> oldest = meshes.values().iterator();
                oldest.next().close();
                oldest.remove();
            }
            mesh = BakedMesh.bake(buffers -> emit(snapshot, buffers, light, level, where, blocks));
            meshes.put(light, mesh);
        }
        return mesh;
    }

    private void emit(GlobeSnapshot snapshot, MultiBufferSource buffers, int light, ClientLevel level, BlockPos where,
                      BlockRenderDispatcher blocks)
    {
        PoseStack poseStack = new PoseStack();
        for (BlockPos pos : snapshot.visibleBlocks())
        {
            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            blockEntities.renderModel(pos, snapshot.get(pos.getX(), pos.getY(), pos.getZ()), poseStack, buffers, light, blocks);
            poseStack.popPose();
        }
        if (!snapshot.fluidCells().isEmpty())
        {
            // Minecraft's water renderer, fed with the snapshot as its world; it writes in cell units.
            SnapshotView view = new SnapshotView(snapshot, level, where, light);
            SnapshotView.PosedConsumer out = new SnapshotView.PosedConsumer(buffers.getBuffer(Sheets.translucentCullBlockSheet()), poseStack.last());
            for (BlockPos pos : snapshot.fluidCells())
            {
                BlockState block = snapshot.get(pos.getX(), pos.getY(), pos.getZ());
                blocks.renderLiquid(pos, view, out.at(pos), block, block.getFluidState());
            }
        }
    }

    @Override
    public void close()
    {
        meshes.values().forEach(BakedMesh::close);
        meshes.clear();
    }
}
