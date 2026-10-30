package com.irisdlss.mixin;

import com.irisdlss.IrisDlss;
import com.irisdlss.WorldAnchoredHud;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * wWaypoints draws its in-world icons and labels into the HUD (Hud.extractRenderState, through its own
 * GuiGraphics wrapper). While frame generation runs, the same call is made again with a wrapper over
 * {@link WorldAnchoredHud}'s separate GUI state, and the HUD one is skipped. Only applied when wWaypoints
 * is installed; reflection because it is not on the compile classpath.
 */
@Pseudo
@Mixin(targets = "com.wwaypoints.render.WaypointOverlayLabelRenderer", remap = false)
public class WWaypointsOverlayMixin {

    @Unique
    private static boolean irisdlss$redirecting;
    @Unique
    private static boolean irisdlss$failed;
    @Unique
    private static Constructor<?> irisdlss$graphics;
    @Unique
    private static Method irisdlss$render;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void irisdlss$intoWorldLayer(@Coerce Object graphics, DeltaTracker delta, @Coerce Object manager,
        @Coerce Object config, CallbackInfo ci) {
        if (irisdlss$redirecting || irisdlss$failed || !WorldAnchoredHud.active()) {
            return;
        }
        irisdlss$redirecting = true;
        try {
            if (irisdlss$render == null) {
                Class<?> graphicsClass = graphics.getClass();
                irisdlss$graphics = graphicsClass.getConstructor(GuiGraphicsExtractor.class);
                Class<?> renderer = Class.forName("com.wwaypoints.render.WaypointOverlayLabelRenderer", false,
                    graphicsClass.getClassLoader());
                for (Method method : renderer.getMethods()) {
                    if (method.getName().equals("render") && method.getParameterCount() == 4
                        && method.getParameterTypes()[0] == graphicsClass) {
                        irisdlss$render = method;
                    }
                }
                if (irisdlss$render == null) {
                    throw new NoSuchMethodException("WaypointOverlayLabelRenderer.render");
                }
            }
            irisdlss$render.invoke(null, irisdlss$graphics.newInstance(WorldAnchoredHud.extractor()), delta, manager,
                config);
            ci.cancel();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            irisdlss$failed = true;
            IrisDlss.LOGGER.warn("Iris DLSS: could not move wWaypoints' icons out of the HUD, they stay as they are", e);
        } finally {
            irisdlss$redirecting = false;
        }
    }
}
