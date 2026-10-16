package com.nick.snowglobe.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Geometry built once and kept on the graphics card, then drawn each frame with a single call per render type,
 * the way Minecraft draws chunks. A miniature with tens of thousands of blocks costs about as much to draw as one
 * with a handful. Must be closed when no longer needed, to free the graphics memory.
 */
final class BakedMesh implements AutoCloseable
{
    private final Map<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();

    private BakedMesh() {}

    /** Runs {@code emit} against buffers that record instead of drawing, and keeps what it drew. Render thread only. */
    static BakedMesh bake(Consumer<MultiBufferSource> emit)
    {
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        Map<RenderType, ByteBufferBuilder> memory = new LinkedHashMap<>();
        MultiBufferSource recorder = type -> builders.computeIfAbsent(type, t -> {
            ByteBufferBuilder bytes = new ByteBufferBuilder(t.bufferSize());
            memory.put(t, bytes);
            return new BufferBuilder(bytes, t.mode(), t.format());
        });
        emit.accept(recorder);

        BakedMesh mesh = new BakedMesh();
        builders.forEach((type, builder) -> {
            MeshData data = builder.build();
            if (data != null)
            {
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(data);
                VertexBuffer.unbind();
                mesh.buffers.put(type, buffer);
            }
            memory.get(type).close();
        });
        return mesh;
    }

    boolean isEmpty()
    {
        return buffers.isEmpty();
    }

    /** Draws the mesh through {@code pose} (on top of the render system's current model-view). */
    void draw(Matrix4f pose)
    {
        if (buffers.isEmpty())
            return;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
        buffers.forEach((type, buffer) -> {
            type.setupRenderState();
            buffer.bind();
            buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
            VertexBuffer.unbind();
            type.clearRenderState();
        });
    }

    @Override
    public void close()
    {
        buffers.values().forEach(VertexBuffer::close);
        buffers.clear();
    }
}
