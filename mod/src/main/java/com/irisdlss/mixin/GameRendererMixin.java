package com.irisdlss.mixin;

import com.irisdlss.DlssUpscaler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two main targets: the world (vanilla, Iris, Voxy) renders into a DLSS-sized one, the GUI into a
 * window-sized one. The field is swapped around the world/GUI boundary of GameRenderer.render, so
 * everything that asks for "the main render target" gets the right one for its phase.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @Shadow
    public abstract GameRenderState gameRenderState();

    @Shadow
    public abstract void resize(int width, int height);

    /** Dev option -Diris-dlss.debugSpin=degrees per frame. */
    @Unique
    private static final float IRISDLSS_DEBUG_SPIN = Float.parseFloat(System.getProperty("iris-dlss.debugSpin", "0"));
    /** Dev option -Diris-dlss.debugWalk=true. */
    @Unique
    private static final boolean IRISDLSS_DEBUG_WALK = Boolean.getBoolean("iris-dlss.debugWalk");

    @Unique
    private RenderTarget irisdlss$low;
    @Unique
    private RenderTarget irisdlss$full;
    @Unique
    private boolean irisdlss$internalResize;
    @Unique
    private boolean irisdlss$pendingResize;
    @Unique
    private boolean irisdlss$frameActive;

    @Inject(method = "render", at = @At("HEAD"))
    private void irisdlss$beginFrame(CallbackInfo ci) {
        if (IRISDLSS_DEBUG_SPIN != 0.0F && net.minecraft.client.Minecraft.getInstance().player != null) {
            // Dev option: steady camera turn, as if the mouse moved the same amount every frame.
            net.minecraft.client.Minecraft.getInstance().player.turn(IRISDLSS_DEBUG_SPIN / 0.15F, 0.0);
        }
        if (IRISDLSS_DEBUG_WALK && net.minecraft.client.Minecraft.getInstance().player != null) {
            // Dev option: hold "forward".
            net.minecraft.client.Minecraft.getInstance().options.keyUp.setDown(true);
        }
        WindowRenderState window = this.gameRenderState().windowRenderState;
        this.irisdlss$frameActive = DlssUpscaler.prepare(window.width, window.height);
        if (!this.irisdlss$frameActive) {
            if (this.irisdlss$full != null) {
                // DLSS turned off or failed: back to the game's own main target at full size. Iris
                // keeps state on that object, so never hand the game our extra GUI target instead.
                this.mainRenderTarget = this.irisdlss$low;
                this.irisdlss$full.destroyBuffers();
                this.irisdlss$full = null;
                this.irisdlss$low = null;
                this.irisdlss$resize(window.width, window.height);
            }
            return;
        }
        if (this.irisdlss$low == null) {
            this.irisdlss$low = this.mainRenderTarget;
            this.irisdlss$full = new MainTarget(window.width, window.height);
        }
        if (this.irisdlss$full.width != window.width || this.irisdlss$full.height != window.height) {
            this.irisdlss$full.resize(window.width, window.height);
        }
        this.mainRenderTarget = this.irisdlss$low;
        if (this.irisdlss$pendingResize || this.irisdlss$low.width != DlssUpscaler.renderWidth()
            || this.irisdlss$low.height != DlssUpscaler.renderHeight()) {
            this.irisdlss$pendingResize = false;
            this.irisdlss$resize(DlssUpscaler.renderWidth(), DlssUpscaler.renderHeight());
            DlssUpscaler.requestReset();
        }
    }

    @Unique
    private void irisdlss$resize(int width, int height) {
        this.irisdlss$internalResize = true;
        try {
            this.resize(width, height);
        } finally {
            this.irisdlss$internalResize = false;
        }
    }

    /** Window resizes from outside the frame: only the GUI target follows; the rest next frame. */
    @Inject(method = "resize", at = @At("HEAD"), cancellable = true)
    private void irisdlss$onResize(int width, int height, CallbackInfo ci) {
        if (this.irisdlss$internalResize || this.irisdlss$full == null) {
            return;
        }
        this.irisdlss$full.resize(width, height);
        this.irisdlss$pendingResize = true;
        ci.cancel();
    }

    /** render() compares the window with the main target; with DLSS the main target is smaller. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V"))
    private void irisdlss$skipWindowSizedResize(GameRenderer instance, int width, int height, Operation<Void> original) {
        if (!this.irisdlss$frameActive) {
            original.call(instance, width, height);
        }
    }

    /** The hand pass clears the depth buffer: keep the world's depth for motion vectors first. */
    @Inject(method = "render3dHud", at = @At("HEAD"))
    private void irisdlss$saveWorldDepth(CameraRenderState cameraState, PlayerRenderState playerState,
        OptionsRenderState optionsState, boolean consistentDepthRequired, CallbackInfo ci) {
        if (this.irisdlss$frameActive) {
            DlssUpscaler.saveWorldDepth(this.mainRenderTarget, consistentDepthRequired);
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
    private void irisdlss$captureProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix) {
        DlssUpscaler.captureProjection(projectionMatrix);
    }

    /** End of the world phase (after Iris composite and vanilla post effects): upscale, then GUI. */
    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void irisdlss$upscale(CallbackInfo ci) {
        if (!this.irisdlss$frameActive) {
            return;
        }
        if (this.gameRenderState().shouldRenderLevel) {
            DlssUpscaler.process(this.irisdlss$low, this.irisdlss$full);
        } else {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                this.irisdlss$full.getColorTexture(), this.gameRenderState().guiRenderState.clearColorOverride,
                this.irisdlss$full.getDepthTexture(), 0.0);
            DlssUpscaler.requestReset();
        }
        this.mainRenderTarget = this.irisdlss$full;
    }
}
