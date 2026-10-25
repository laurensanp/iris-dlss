package com.irisdlss.mixin;

import com.irisdlss.DlssNative;
import com.irisdlss.IrisDlss;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The game's VkInstance gets NGX's instance extensions (the bridge adds them). */
@Mixin(VulkanInstance.class)
public class VulkanInstanceMixin {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private int irisdlss$createInstance(VkInstanceCreateInfo info, VkAllocationCallbacks allocator, PointerBuffer instance,
        Operation<Integer> original) {
        if (!IrisDlss.nativeReady()) {
            return original.call(info, allocator, instance);
        }
        return DlssNative.createInstance(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"),
            info.address(), allocator == null ? 0L : allocator.address(), instance.address());
    }
}
