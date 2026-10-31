package com.siris;

import com.siris.mixin.VulkanCommandEncoderAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;

/** Raw Vulkan handles behind RenderPearl's objects, for recording DLSS into the game's frame. */
public final class VulkanAccess {

    /** VkImageUsageFlags added to the next texture RenderPearl creates (see VulkanConstMixin). */
    public static int extraImageUsage;

    private static final int VK_IMAGE_USAGE_STORAGE_BIT = 0x8;

    private VulkanAccess() {
    }

    /** True when the game runs on RenderPearl's Vulkan backend. */
    public static boolean isVulkan() {
        return RenderSystem.tryGetDevice() != null && encoder() != null;
    }

    public static VulkanCommandEncoder encoder() {
        if (RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder frontend
            && frontend.backend() instanceof VulkanCommandEncoder vulkan) {
            return vulkan;
        }
        return null;
    }

    /** The game's command buffer being recorded right now (outside any render pass). */
    public static long commandBuffer() {
        return ((VulkanCommandEncoderAccessor) encoder()).siris$commandBuffer().address();
    }

    /** {VkImage, VkImageView, VkFormat, width, height} as the bridge's SirisImage. */
    public static long[] describe(GpuTextureView view) {
        VulkanGpuTexture texture = (VulkanGpuTexture) view.texture();
        return new long[] {texture.vkImage(), ((VulkanGpuTextureView) view).vkImageView(),
            VulkanConst.toVk(texture.getFormat()), view.getWidth(0), view.getHeight(0)};
    }

    public static long[] concat(long[]... parts) {
        int length = 0;
        for (long[] part : parts) {
            length += part.length;
        }
        long[] all = new long[length];
        int offset = 0;
        for (long[] part : parts) {
            System.arraycopy(part, 0, all, offset, part.length);
            offset += part.length;
        }
        return all;
    }

    /** A texture DLSS may write as a storage image (RenderPearl never asks for that usage itself). */
    public static GpuTexture createStorageTexture(String label, int usage, com.mojang.renderpearl.api.GpuFormat format,
        int width, int height) {
        extraImageUsage = VK_IMAGE_USAGE_STORAGE_BIT;
        try {
            return RenderSystem.getDevice().createTexture(label, usage, format, width, height, 1, 1);
        } finally {
            extraImageUsage = 0;
        }
    }
}
