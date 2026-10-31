package com.siris.mixin;

import com.siris.DlssConfig;
import com.siris.DlssUpscaler;
import com.siris.FrameStats;
import com.siris.VulkanPresenter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The performance overlay (setting "Overlay"): fps shown / rendered in the top left corner of the HUD. */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Shadow
    public abstract Font getFont();

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("TAIL"))
    private void siris$overlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (DlssConfig.overlay == DlssConfig.Overlay.OFF || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        int rendered = minecraft.getFps();
        int renderedLow = Math.round(FrameStats.renderedLow());
        if (VulkanPresenter.isRunning()) {
            int shown = Math.round(VulkanPresenter.shownFps());
            int shownLow = Math.round(VulkanPresenter.shownLowFps());
            lines.add(DlssConfig.frameGeneration && shown > rendered * 1.3F
                ? shown + " fps, 1% low " + shownLow + " (rendered " + rendered + ", 1% low " + renderedLow
                    + ", Frame Generation)"
                : shown + " fps, 1% low " + shownLow);
        } else {
            lines.add(rendered + " fps, 1% low " + renderedLow);
        }
        if (DlssConfig.overlay == DlssConfig.Overlay.DETAILED) {
            lines.add(DlssUpscaler.statusLine());
            lines.add(VulkanPresenter.compactStatus());
        }
        Font font = this.getFont();
        int y = 2;
        for (String line : lines) {
            graphics.fill(1, y - 1, 3 + font.width(line), y + font.lineHeight, 0x90000000);
            graphics.text(font, line, 2, y, 0xFFFFFFFF, false);
            y += font.lineHeight + 1;
        }
    }
}
