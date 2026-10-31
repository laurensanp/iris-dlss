package com.siris.mixin;

import com.siris.Siris;
import com.siris.WorldAnchoredHud;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
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
    private static boolean siris$redirecting;
    @Unique
    private static boolean siris$failed;
    @Unique
    private static Constructor<?> siris$graphics;
    @Unique
    private static Method siris$render;

    @Unique
    private static Method siris$waypoints;
    @Unique
    private static Method siris$position;

    /** Block centres of the manager's waypoints, for the icons' motion vectors. */
    @Unique
    private static void siris$collectAnchors(Object manager) throws ReflectiveOperationException {
        if (siris$waypoints == null) {
            siris$waypoints = manager.getClass().getMethod("getWaypoints");
        }
        for (Object waypoint : (Iterable<?>) siris$waypoints.invoke(manager)) {
            if (siris$position == null) {
                siris$position = waypoint.getClass().getMethod("getPosition");
            }
            if (siris$position.invoke(waypoint) instanceof BlockPos pos) {
                WorldAnchoredHud.addAnchor(Vec3.atCenterOf(pos));
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void siris$intoWorldLayer(@Coerce Object graphics, DeltaTracker delta, @Coerce Object manager,
        @Coerce Object config, CallbackInfo ci) {
        if (siris$redirecting || siris$failed || !WorldAnchoredHud.active()) {
            return;
        }
        siris$redirecting = true;
        try {
            if (siris$render == null) {
                Class<?> graphicsClass = graphics.getClass();
                siris$graphics = graphicsClass.getConstructor(GuiGraphicsExtractor.class);
                Class<?> renderer = Class.forName("com.wwaypoints.render.WaypointOverlayLabelRenderer", false,
                    graphicsClass.getClassLoader());
                for (Method method : renderer.getMethods()) {
                    if (method.getName().equals("render") && method.getParameterCount() == 4
                        && method.getParameterTypes()[0] == graphicsClass) {
                        siris$render = method;
                    }
                }
                if (siris$render == null) {
                    throw new NoSuchMethodException("WaypointOverlayLabelRenderer.render");
                }
            }
            siris$render.invoke(null, siris$graphics.newInstance(WorldAnchoredHud.extractor()), delta, manager,
                config);
            siris$collectAnchors(manager);
            ci.cancel();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            siris$failed = true;
            Siris.LOGGER.warn("SIRIS: could not move wWaypoints' icons out of the HUD, they stay as they are", e);
        } finally {
            siris$redirecting = false;
        }
    }
}
