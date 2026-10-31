package com.siris.mixin;

import com.siris.Siris;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DLSS runs inside the game's Vulkan device: try Vulkan first whatever the "Graphics API" option
 * says. OpenGL stays as the fallback so the game still starts where Vulkan is missing (DLSS is off then).
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {

    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void siris$vulkanFirst(CallbackInfoReturnable<GpuBackend[]> cir) {
        if (Siris.nativeReady()) {
            Siris.LOGGER.info("SIRIS: using the Vulkan graphics backend");
            cir.setReturnValue(new GpuBackend[] {new VulkanBackend(), new GlBackend()});
        }
    }
}
