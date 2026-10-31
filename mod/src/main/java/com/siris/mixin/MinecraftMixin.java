package com.siris.mixin;

import com.siris.DevScreens;
import com.siris.DevTest;
import com.siris.DlssBenchmark;
import com.siris.EntityMotion;
import com.siris.FrameStats;
import com.siris.GpuAllocationStats;
import com.siris.MemoryCheck;
import com.siris.VulkanPresenter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    /** Frame start, before mouse and keyboard are processed: the low-latency wait goes here. */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void siris$frameStart(boolean advanceGameTime, CallbackInfo ci) {
        FrameStats.frame();
        VulkanPresenter.frameStart();
        DlssBenchmark.frame();
        DevScreens.frame();
        GpuAllocationStats.frame();
        EntityMotion.frameStart();
        MemoryCheck.frame();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void siris$devTest(CallbackInfo ci) {
        DevTest.tick((Minecraft) (Object) this);
    }
}
