package com.irisdlss.mixin;

import com.irisdlss.DlssUpscaler;
import com.irisdlss.GpuAllocationStats;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NGX goes before the device: after the game waited for its queue, before vkDestroyDevice. Diagnostics: allocations by label. */
@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaDestroyAllocator(J)V"))
    private void irisdlss$shutdown(CallbackInfo ci) {
        DlssUpscaler.shutdown();
    }

    /** Diagnostics: what fills video memory (GpuAllocationStats). */
    @Inject(method = "createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;",
        at = @At("HEAD"))
    private void irisdlss$countTexture(String label, int usage, GpuFormat format, int width, int height, int depth, int mips,
        CallbackInfoReturnable<GpuTexture> cir) {
        if (GpuAllocationStats.enabled()) {
            GpuAllocationStats.texture(label, width, height, depth, mips, format.blockSize());
        }
    }

    @Inject(method = "createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/renderpearl/backend/vulkan/VulkanGpuBuffer;",
        at = @At("HEAD"))
    private void irisdlss$countBuffer(Supplier<String> label, int usage, long size, CallbackInfoReturnable<VulkanGpuBuffer> cir) {
        if (GpuAllocationStats.enabled()) {
            GpuAllocationStats.buffer(label, size);
        }
    }
}
