package com.irisdlss.mixin;

import com.irisdlss.VulkanPresenter;
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

    @Unique
    private long irisdlss$window;
    @Unique
    private boolean irisdlss$skippedAcquire;
    @Unique
    private boolean irisdlss$forceAcquire;
    @Unique
    private boolean irisdlss$skipPresent;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void irisdlss$window(VulkanDevice device, long windowHandle, CallbackInfo ci) {
        this.irisdlss$window = windowHandle;
    }

    @Inject(method = "configure", at = @At("TAIL"))
    private void irisdlss$vsync(GpuSurface.Configuration config, CallbackInfo ci) {
        VulkanPresenter.setVsync(config.presentMode() == GpuSurface.PresentMode.FIFO);
    }

    @Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
    private void irisdlss$acquire(CallbackInfo ci) {
        if (!this.irisdlss$forceAcquire && VulkanPresenter.wanted()) {
            this.irisdlss$skippedAcquire = true;
            ci.cancel();
        }
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
    private void irisdlss$present(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
        if (!this.irisdlss$skippedAcquire) {
            // The game presents this frame itself: the child window must not cover it.
            VulkanPresenter.stop();
            return;
        }
        this.irisdlss$skippedAcquire = false;
        if (VulkanPresenter.present(this.irisdlss$window, textureView)) {
            this.irisdlss$skipPresent = true;
            ci.cancel();
            return;
        }
        this.irisdlss$forceAcquire = true;
        try {
            this.acquireNextTexture();
        } catch (SurfaceException e) {
            // Out of date: nothing to show now; the next acquire reports it so the game reconfigures.
            this.eatenException = e;
            this.irisdlss$skipPresent = true;
            ci.cancel();
        } finally {
            this.irisdlss$forceAcquire = false;
        }
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void irisdlss$skipPresent(CallbackInfo ci) {
        if (this.irisdlss$skipPresent) {
            this.irisdlss$skipPresent = false;
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void irisdlss$close(CallbackInfo ci) {
        VulkanPresenter.stop();
    }
}
