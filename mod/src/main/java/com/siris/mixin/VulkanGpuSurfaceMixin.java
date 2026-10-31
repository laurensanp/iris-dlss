package com.siris.mixin;

import com.siris.VulkanPresenter;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The window output: while the presenter runs the game's swapchain is left alone (no image
 * acquired, nothing presented) and the frame goes to the presenter instead. Whether it runs is
 * decided at acquire time; if it then declines the frame, the image is acquired late and the game
 * presents as usual.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {

    @Shadow
    private SurfaceException eatenException;

    @Shadow
    public abstract void acquireNextTexture() throws SurfaceException;

    /** Dev option -Dsiris.debugForeignAcquire=true: behave as if another mod acquired the image (Lunar Client). */
    @Unique
    private static final boolean SIRIS_FOREIGN_ACQUIRE = Boolean.getBoolean("siris.debugForeignAcquire");

    @Unique
    private long siris$window;
    @Unique
    private boolean siris$skippedAcquire;
    @Unique
    private boolean siris$forceAcquire;
    @Unique
    private boolean siris$skipPresent;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void siris$window(VulkanDevice device, long windowHandle, CallbackInfo ci) {
        this.siris$window = windowHandle;
    }

    @Inject(method = "configure", at = @At("TAIL"))
    private void siris$vsync(GpuSurface.Configuration config, CallbackInfo ci) {
        VulkanPresenter.setVsync(config.presentMode() == GpuSurface.PresentMode.FIFO);
    }

    @Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
    private void siris$acquire(CallbackInfo ci) {
        if (!this.siris$forceAcquire && !SIRIS_FOREIGN_ACQUIRE && VulkanPresenter.wanted()) {
            this.siris$skippedAcquire = true;
            ci.cancel();
        }
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
    private void siris$present(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
        if (!this.siris$skippedAcquire) {
            if (VulkanPresenter.wanted()) {
                // Something else acquired the image without acquireNextTexture (Lunar Client's
                // Multi-Threaded Rendering acquires and presents on its own threads).
                VulkanPresenter.foreignSwapchain();
            }
            // The game presents this frame itself: the child window must not cover it.
            VulkanPresenter.stop();
            return;
        }
        this.siris$skippedAcquire = false;
        VulkanPresenter.ownSwapchain();
        if (VulkanPresenter.present(this.siris$window, textureView)) {
            this.siris$skipPresent = true;
            ci.cancel();
            return;
        }
        this.siris$forceAcquire = true;
        try {
            this.acquireNextTexture();
        } catch (SurfaceException e) {
            // Out of date: nothing to show now; the next acquire reports it so the game reconfigures.
            this.eatenException = e;
            this.siris$skipPresent = true;
            ci.cancel();
        } finally {
            this.siris$forceAcquire = false;
        }
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void siris$skipPresent(CallbackInfo ci) {
        if (this.siris$skipPresent) {
            this.siris$skipPresent = false;
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void siris$close(CallbackInfo ci) {
        VulkanPresenter.stop();
    }
}
