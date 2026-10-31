package com.siris.mixin;

import com.siris.ShaderPackTweaks;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vitrail reads every pack source file as lines before resolving its #if blocks: there the pack's
 * TAA-only dither animation is turned on (ShaderPackTweaks.animateDither). Only applied when Vitrail
 * is installed.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.pack.source.IncludeExpander", remap = false)
public class VitrailDitherMixin {

    @ModifyVariable(method = "logicalLines(Ljava/util/List;)Ljava/util/List;", at = @At("HEAD"), argsOnly = true,
        require = 0, remap = false)
    private static List<String> siris$animateDither(List<String> lines) {
        return ShaderPackTweaks.animateDither(lines);
    }
}
