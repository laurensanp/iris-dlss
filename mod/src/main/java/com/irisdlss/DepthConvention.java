package com.irisdlss;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Method;
import org.joml.Matrix4f;

/**
 * Which depth the main depth buffer holds this frame.
 *
 * <p>Vanilla 26.3 renders reverse-Z (near = 1). Iris hides GL_ARB_clip_control from RenderPearl
 * (mixin UndoReverseZTwo) and, while a shader pack is active, renders classic depth with the
 * projection from its MatrixUtils.undoRevZ. Without clip control, NDC z is [-1, 1] stored as
 * depth * 2 - 1.
 */
public final class DepthConvention {

    private static boolean irisLookedUp;
    private static Object irisApi;
    private static Method isShaderPackInUse;
    private static Method undoRevZ;

    private DepthConvention() {
    }

    private static void lookUpIris() {
        if (irisLookedUp) {
            return;
        }
        irisLookedUp = true;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = api.getMethod("getInstance").invoke(null);
            isShaderPackInUse = api.getMethod("isShaderPackInUse");
            undoRevZ = Class.forName("net.irisshaders.iris.helpers.MatrixUtils")
                .getMethod("undoRevZ", Matrix4f.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            irisApi = null;
        }
    }

    /** True when an Iris shader pack renders the world (classic, non-reversed depth). */
    public static boolean shaderPackActive() {
        lookUpIris();
        if (irisApi == null) {
            return false;
        }
        try {
            return (boolean) isShaderPackInUse.invoke(irisApi);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** True when NDC z equals the stored depth (clip control on); otherwise NDC z = depth * 2 - 1. */
    public static boolean zeroToOne() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    }

    /** Depth buffer has near = 1 (vanilla reverse-Z), as DLSS's DepthInverted flag means it. */
    public static boolean depthInverted() {
        return !shaderPackActive();
    }

    /** The projection whose depth output matches the depth buffer. */
    public static Matrix4f projectionForDepth(Matrix4f vanillaProjection) {
        if (!shaderPackActive()) {
            return new Matrix4f(vanillaProjection);
        }
        try {
            return (Matrix4f) undoRevZ.invoke(null, new Matrix4f(vanillaProjection));
        } catch (ReflectiveOperationException e) {
            return new Matrix4f(vanillaProjection);
        }
    }
}
