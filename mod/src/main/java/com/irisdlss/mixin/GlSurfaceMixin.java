package com.irisdlss.mixin;

import com.irisdlss.VulkanPresenter;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The window output: Vulkan swapchain instead of the OpenGL blit + swap while it runs. */
@Mixin(GlSurface.class)
public abstract class GlSurfaceMixin {

    @Shadow
    @Final
    private long windowHandle;

    @Inject(method = "configure", at = @At("TAIL"))
    private void irisdlss$vsync(GpuSurface.Configuration config, CallbackInfo ci) {
        VulkanPresenter.setVsync(config.presentMode() == GpuSurface.PresentMode.FIFO);
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
    private void irisdlss$present(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
        if (VulkanPresenter.present(this.windowHandle, textureView)) {
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void irisdlss$close(CallbackInfo ci) {
        VulkanPresenter.stop();
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void irisdlss$skipSwap(CallbackInfo ci) {
        if (VulkanPresenter.consumeSwap()) {
            ci.cancel();
        }
    }
}
