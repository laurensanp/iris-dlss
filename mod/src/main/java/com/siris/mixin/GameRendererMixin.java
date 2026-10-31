package com.siris.mixin;

import com.siris.DlssUpscaler;
import com.siris.WorldAnchoredHud;
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
 * Two main targets: the world (vanilla, shader pack, Voxy) renders into a DLSS-sized one, the GUI into a
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

    /** Dev option -Dsiris.debugSpin=degrees per frame. */
    @Unique
    private static final float SIRIS_DEBUG_SPIN = Float.parseFloat(System.getProperty("siris.debugSpin", "0"));
    /** Dev option -Dsiris.debugWalk=true. */
    @Unique
    private static final boolean SIRIS_DEBUG_WALK = Boolean.getBoolean("siris.debugWalk");

    @Unique
    private RenderTarget siris$low;
    @Unique
    private RenderTarget siris$full;
    @Unique
    private boolean siris$internalResize;
    @Unique
    private boolean siris$pendingResize;
    @Unique
    private boolean siris$frameActive;

    @Inject(method = "render", at = @At("HEAD"))
    private void siris$beginFrame(CallbackInfo ci) {
        if (SIRIS_DEBUG_SPIN != 0.0F && net.minecraft.client.Minecraft.getInstance().player != null) {
            // Dev option: steady camera turn, as if the mouse moved the same amount every frame.
            net.minecraft.client.Minecraft.getInstance().player.turn(SIRIS_DEBUG_SPIN / 0.15F, 0.0);
        }
        if (SIRIS_DEBUG_WALK && net.minecraft.client.Minecraft.getInstance().player != null) {
            // Dev option: hold "forward".
            net.minecraft.client.Minecraft.getInstance().options.keyUp.setDown(true);
        }
        WindowRenderState window = this.gameRenderState().windowRenderState;
        this.siris$frameActive = DlssUpscaler.prepare(window.width, window.height);
        if (!this.siris$frameActive) {
            if (this.siris$full != null) {
                // DLSS turned off or failed: back to the game's own main target at full size. Shader mods
                // keep state on that object, so never hand the game our extra GUI target instead.
                this.mainRenderTarget = this.siris$low;
                this.siris$full.destroyBuffers();
                this.siris$full = null;
                this.siris$low = null;
                this.siris$resize(window.width, window.height);
            }
            return;
        }
        if (this.siris$low == null) {
            this.siris$low = this.mainRenderTarget;
            this.siris$full = new MainTarget(window.width, window.height);
        }
        if (this.siris$full.width != window.width || this.siris$full.height != window.height) {
            this.siris$full.resize(window.width, window.height);
        }
        this.mainRenderTarget = this.siris$low;
        if (this.siris$pendingResize || this.siris$low.width != DlssUpscaler.renderWidth()
            || this.siris$low.height != DlssUpscaler.renderHeight()) {
            this.siris$pendingResize = false;
            this.siris$resize(DlssUpscaler.renderWidth(), DlssUpscaler.renderHeight());
            DlssUpscaler.requestReset();
        }
    }

    @Unique
    private void siris$resize(int width, int height) {
        this.siris$internalResize = true;
        try {
            this.resize(width, height);
        } finally {
            this.siris$internalResize = false;
        }
    }

    /** Window resizes from outside the frame: only the GUI target follows; the rest next frame. */
    @Inject(method = "resize", at = @At("HEAD"), cancellable = true)
    private void siris$onResize(int width, int height, CallbackInfo ci) {
        if (this.siris$internalResize || this.siris$full == null) {
            return;
        }
        this.siris$full.resize(width, height);
        this.siris$pendingResize = true;
        ci.cancel();
    }

    /** render() compares the window with the main target; with DLSS the main target is smaller. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V"))
    private void siris$skipWindowSizedResize(GameRenderer instance, int width, int height, Operation<Void> original) {
        if (!this.siris$frameActive) {
            original.call(instance, width, height);
        }
    }

    /** The hand pass clears the depth buffer: keep the world's depth for motion vectors first. */
    @Inject(method = "render3dHud", at = @At("HEAD"))
    private void siris$saveWorldDepth(CameraRenderState cameraState, PlayerRenderState playerState,
        OptionsRenderState optionsState, boolean consistentDepthRequired, CallbackInfo ci) {
        if (this.siris$frameActive) {
            DlssUpscaler.saveWorldDepth(this.mainRenderTarget, consistentDepthRequired);
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
    private void siris$captureProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix) {
        DlssUpscaler.captureProjection(projectionMatrix);
    }

    /** Right before the GUI: world-anchored HUD (waypoint icons) onto the picture frame generation moves. */
    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void siris$worldAnchoredHud(CallbackInfo ci) {
        if (this.siris$frameActive) {
            WorldAnchoredHud.renderBeforeGui();
        }
    }

    /** End of the world phase (after the pack's composite/final and vanilla post effects): upscale, then GUI. */
    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void siris$upscale(CallbackInfo ci) {
        if (!this.siris$frameActive) {
            return;
        }
        if (this.gameRenderState().shouldRenderLevel) {
            DlssUpscaler.process(this.siris$low, this.siris$full);
        } else {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                this.siris$full.getColorTexture(), this.gameRenderState().guiRenderState.clearColorOverride,
                this.siris$full.getDepthTexture(), 0.0);
            DlssUpscaler.requestReset();
        }
        this.mainRenderTarget = this.siris$full;
    }
}
