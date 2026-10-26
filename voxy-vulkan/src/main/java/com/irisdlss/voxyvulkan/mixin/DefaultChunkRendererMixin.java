package com.irisdlss.voxyvulkan.mixin;

import com.irisdlss.voxyvulkan.LodRenderer;
import com.irisdlss.voxyvulkan.VoxyVulkan;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LODs are drawn in Sodium's opaque terrain pass, into the same targets and depth as the terrain. */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class DefaultChunkRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void voxyvulkan$drawLods(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
        TerrainRenderPass terrainPass, CameraTransform camera, FogParameters fog, boolean indexed, RenderPass pass,
        GpuSampler sampler, GpuBufferSlice slice, GpuBuffer buffer, OitStage oit, CallbackInfo ci) {
        if (terrainPass == DefaultTerrainRenderPasses.SOLID && VoxyVulkan.isVulkan()) {
            LodRenderer.render(pass, matrices, camera);
        }
    }
}
