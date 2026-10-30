package com.irisdlss.mixin;

import com.irisdlss.IrisDlss;
import com.irisdlss.ShaderPackTweaks;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vitrail reads, translates and compiles the whole pack a second time when a world is joined, only so
 * that block tags in block.properties can resolve against the world's registries. For a pack without
 * tags (Solas) that is a 1-2 s hitch for nothing (Lunar logs it as "Pause - Unknown"): when the
 * registry stamp is the only reason, and the pack names no tag, the pack is marked current instead.
 * A dimension change, Distant Horizons or a resource pack material change still reload. Only applied
 * when Vitrail is installed; reflection because Vitrail is not on the compile classpath.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.render.PackChoice", remap = false)
public class VitrailJoinReloadMixin {

    @Unique
    private static Method irisdlss$moved;
    @Unique
    private static Method irisdlss$distantMoved;
    @Unique
    private static Method irisdlss$formatMoved;
    @Unique
    private static Method irisdlss$stale;
    @Unique
    private static Method irisdlss$settle;
    @Unique
    private static boolean irisdlss$failed;
    @Unique
    private static boolean irisdlss$logged;

    @Inject(method = "reloadIfTheWorldMoved", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void irisdlss$skipTaglessReload(Path gameDirectory, CallbackInfoReturnable<Boolean> cir) {
        if (irisdlss$failed) {
            return;
        }
        try {
            if (irisdlss$stale == null) {
                ClassLoader loader = VitrailJoinReloadMixin.class.getClassLoader();
                Class<?> defines = Class.forName("dev.vitrail.render.PackDefines", false, loader);
                Class<?> place = Class.forName("dev.vitrail.render.PackPlace", false, loader);
                irisdlss$moved = place.getMethod("moved");
                irisdlss$distantMoved = defines.getMethod("distantHorizonsMoved");
                irisdlss$formatMoved = defines.getMethod("textureFormatMoved");
                irisdlss$stale = defines.getMethod("stale");
                irisdlss$settle = defines.getMethod("settle");
            }
            if (!(boolean) irisdlss$stale.invoke(null) || (boolean) irisdlss$moved.invoke(null)
                || (boolean) irisdlss$distantMoved.invoke(null) || (boolean) irisdlss$formatMoved.invoke(null)
                || ShaderPackTweaks.selectedPackUsesBlockTags(gameDirectory)) {
                return;
            }
            irisdlss$settle.invoke(null);
            if (!irisdlss$logged) {
                irisdlss$logged = true;
                IrisDlss.LOGGER.warn("Iris DLSS: the shader pack names no block tags, so Vitrail's second read of "
                    + "it on joining a world is skipped (saves the join hitch)");
            }
            cir.setReturnValue(false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            irisdlss$failed = true;
            IrisDlss.LOGGER.warn("Iris DLSS: could not check Vitrail's world-join reload, left as it is", e);
        }
    }
}
