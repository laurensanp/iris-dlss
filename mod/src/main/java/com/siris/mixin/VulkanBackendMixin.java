package com.siris.mixin;

import com.siris.DlssNative;
import com.siris.Siris;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game's VkDevice is created by the bridge: same request plus NGX's extensions and features, a
 * queue for the presenter thread and an optical-flow queue for frame generation.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {

    @WrapOperation(
        method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int siris$createDevice(VkPhysicalDevice physicalDevice, VkDeviceCreateInfo info,
        VkAllocationCallbacks allocator, PointerBuffer device, Operation<Integer> original) {
        if (!Siris.nativeReady()) {
            return original.call(physicalDevice, info, allocator, device);
        }
        int result = DlssNative.createDevice(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"),
            physicalDevice.getInstance().address(), physicalDevice.address(), info.address(),
            allocator == null ? 0L : allocator.address(), device.address());
        Siris.LOGGER.info("SIRIS: {}", DlssNative.message());
        return result;
    }
}
