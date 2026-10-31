package com.siris.mixin;

import com.siris.SamplerLodBias;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGpuSampler.class)
public class VulkanGpuSamplerMixin {

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"))
    private float siris$lodBias(float bias) {
        return bias + SamplerLodBias.vulkanBias();
    }
}
