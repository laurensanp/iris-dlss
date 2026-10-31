package com.siris.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {

    /** The frame's command buffer being recorded (allocated on first use). */
    @Invoker("commandBuffer")
    VkCommandBuffer siris$commandBuffer();
}
