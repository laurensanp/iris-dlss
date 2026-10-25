package com.irisdlss.mixin;

import com.irisdlss.DlssBenchmark;
import com.irisdlss.VulkanPresenter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    /** Frame start, before mouse and keyboard are processed: the low-latency wait goes here. */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void irisdlss$frameStart(boolean advanceGameTime, CallbackInfo ci) {
        VulkanPresenter.frameStart();
        DlssBenchmark.frame();
    }
}
