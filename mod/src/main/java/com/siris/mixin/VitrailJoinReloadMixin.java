package com.siris.mixin;

import com.siris.Siris;
import com.siris.ShaderPackTweaks;
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
    private static Method siris$moved;
    @Unique
    private static Method siris$distantMoved;
    @Unique
    private static Method siris$formatMoved;
    @Unique
    private static Method siris$stale;
    @Unique
    private static Method siris$settle;
    @Unique
    private static boolean siris$failed;
    @Unique
    private static boolean siris$logged;

    @Inject(method = "reloadIfTheWorldMoved", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void siris$skipTaglessReload(Path gameDirectory, CallbackInfoReturnable<Boolean> cir) {
        if (siris$failed) {
            return;
        }
        try {
            if (siris$stale == null) {
                ClassLoader loader = VitrailJoinReloadMixin.class.getClassLoader();
                Class<?> defines = Class.forName("dev.vitrail.render.PackDefines", false, loader);
                Class<?> place = Class.forName("dev.vitrail.render.PackPlace", false, loader);
                siris$moved = place.getMethod("moved");
                siris$distantMoved = defines.getMethod("distantHorizonsMoved");
                siris$formatMoved = defines.getMethod("textureFormatMoved");
                siris$stale = defines.getMethod("stale");
                siris$settle = defines.getMethod("settle");
            }
            if (!(boolean) siris$stale.invoke(null) || (boolean) siris$moved.invoke(null)
                || (boolean) siris$distantMoved.invoke(null) || (boolean) siris$formatMoved.invoke(null)
                || ShaderPackTweaks.selectedPackUsesBlockTags(gameDirectory)) {
                return;
            }
            siris$settle.invoke(null);
            if (!siris$logged) {
                siris$logged = true;
                Siris.LOGGER.warn("SIRIS: the shader pack names no block tags, so Vitrail's second read of "
                    + "it on joining a world is skipped (saves the join hitch)");
            }
            cir.setReturnValue(false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            siris$failed = true;
            Siris.LOGGER.warn("SIRIS: could not check Vitrail's world-join reload, left as it is", e);
        }
    }
}
