package com.siris.voxyvulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voxy on Minecraft's Vulkan renderer. Voxy itself only renders through OpenGL and refuses Vulkan; this
 * mod lets the game start on Vulkan with Voxy installed, keeps Voxy's storage and ingest running and draws
 * its LOD sections with an own renderer ({@link LodRenderer}). Voxy's jar is used unchanged.
 */
public final class VoxyVulkan implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("VoxyVulkan");

    private static Boolean vulkan;

    @Override
    public void onInitializeClient() {
    }

    /** The game renders through Vulkan (known once the graphics device exists). */
    public static boolean isVulkan() {
        if (vulkan == null) {
            if (RenderSystem.tryGetDevice() == null) {
                return false;
            }
            vulkan = RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder frontend
                && frontend.backend() instanceof VulkanCommandEncoder;
        }
        return vulkan;
    }
}
