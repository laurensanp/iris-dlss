package com.siris;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;

/**
 * Which depth the main depth buffer holds.
 *
 * <p>Vanilla 26.3 renders reverse-Z (near = 1). Shader packs don't change that on Vulkan: Vitrail
 * keeps drawing the world in reverse-Z and only converts the depth copies it hands the pack. So DLSS
 * always gets reverse-Z depth and vanilla's projection.
 */
public final class DepthConvention {

    private DepthConvention() {
    }

    /** True when NDC z equals the stored depth (clip control on); otherwise NDC z = depth * 2 - 1. */
    public static boolean zeroToOne() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    }

    /** Depth buffer has near = 1 (vanilla reverse-Z), as DLSS's DepthInverted flag means it. */
    public static boolean depthInverted() {
        return true;
    }

    /** The projection whose depth output matches the depth buffer. */
    public static Matrix4f projectionForDepth(Matrix4f vanillaProjection) {
        return new Matrix4f(vanillaProjection);
    }
}
