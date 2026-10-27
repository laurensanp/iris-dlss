package com.irisdlss.mixin;

import com.irisdlss.DlssUpscaler;
import com.irisdlss.IrisDlss;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vitrail's own "Render Scale" (FSR 1) swaps smaller textures into the main target at the world's
 * clear and upscales at the interface's. With DLSS the main target already is the render size, so
 * both together would scale the world twice: while DLSS runs, Vitrail's scale reads as 100 % (its
 * "don't scale" road, which also puts its outline target back). Only applied when Vitrail is installed.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.render.RenderScale", remap = false)
public class VitrailRenderScaleMixin {

    @Unique
    private static boolean irisdlss$logged;

    @ModifyVariable(method = "beginWorld", at = @At("STORE"), ordinal = 0, remap = false)
    private static int irisdlss$noScaleWithDlss(int asked) {
        if (asked >= 100 || !DlssUpscaler.isActive() || !DlssUpscaler.hasResources()) {
            return asked;
        }
        if (!irisdlss$logged) {
            irisdlss$logged = true;
            IrisDlss.LOGGER.info("Iris DLSS: Vitrail's Render Scale ({} %) is ignored while DLSS is on", asked);
        }
        return 100;
    }
}
