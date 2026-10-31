package com.siris.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's vertex buffer pool (GUI, entities, particles, "Shared Buffer") closes every recycled buffer that
 * wasn't used this frame, and recycles only buffers whose fence has signalled by the end of the frame. With the
 * DLSS presenter the GPU is about two frames behind, so the last frame's fence is usually still pending: the next
 * frame finds nothing to reuse and creates new buffers, and recycled ones get closed a frame later. That is about
 * one create + destroy per pool and frame; video memory is freed by the driver only in batches, so it climbs in a
 * sawtooth (Lunar: +250 MB/s up to the 8 GB limit, then the GPU stalls). Here recycled buffers stay available for
 * up to {@link #MAX_IDLE_FRAMES} frames, at most {@link #MAX_KEPT} per pool; they are done on the GPU, so reusing
 * them later is safe.
 */
@Mixin(targets = "net.minecraft.client.renderer.StagedVertexBuffer$GpuBufferPool")
public class StagedVertexBufferPoolMixin {

    @Unique
    private static final int MAX_IDLE_FRAMES = 120;
    @Unique
    private static final int MAX_KEPT = 16;

    @Shadow
    @Final
    private List<GpuBuffer> available;

    /** Buffers kept past the end of a frame, with the frames they have been idle. */
    @Unique
    private final Map<GpuBuffer, Integer> siris$idle = new IdentityHashMap<>();
    @Unique
    private final List<GpuBuffer> siris$kept = new ArrayList<>();

    /** Take the unused buffers out before vanilla closes them. */
    @Inject(method = "endFrame", at = @At("HEAD"))
    private void siris$keepAvailable(CallbackInfo ci) {
        this.siris$kept.clear();
        this.siris$kept.addAll(this.available);
        this.available.clear();
        this.siris$idle.keySet().retainAll(this.siris$kept);
    }

    /** Put them back after vanilla recycled this frame's finished buffers; close the old and the surplus. */
    @Inject(method = "endFrame", at = @At("TAIL"))
    private void siris$restoreAvailable(CallbackInfo ci) {
        for (GpuBuffer buffer : this.siris$kept) {
            int idle = this.siris$idle.merge(buffer, 1, Integer::sum);
            if (idle > MAX_IDLE_FRAMES || this.available.size() >= MAX_KEPT) {
                this.siris$idle.remove(buffer);
                buffer.close();
            } else {
                this.available.add(buffer);
            }
        }
        this.siris$kept.clear();
    }
}
