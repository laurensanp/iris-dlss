package com.siris.voxyvulkan;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Whether a shader pack draws the world: Vitrail's PackChain.drawingPack(), looked up once (Vitrail is optional). */
final class ShaderPacks {

    private static MethodHandle drawingPack;
    private static boolean looked;

    private ShaderPacks() {
    }

    static boolean active() {
        if (!looked) {
            looked = true;
            try {
                Class<?> chain = Class.forName("dev.vitrail.render.PackChain");
                drawingPack = MethodHandles.publicLookup().findStatic(chain, "drawingPack", MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                drawingPack = null;
            }
        }
        try {
            return drawingPack != null && (boolean) drawingPack.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }
}
