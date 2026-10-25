package com.irisdlss;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33;

/**
 * Applies a texture LOD bias to every RenderPearl sampler object. When the world renders below
 * output resolution, mip selection otherwise picks levels for the low resolution and DLSS gets
 * blurry textures to reconstruct from.
 */
public final class SamplerLodBias {

    private static final IntSet samplers = new IntOpenHashSet();
    private static float bias;

    private SamplerLodBias() {
    }

    public static void register(int sampler) {
        samplers.add(sampler);
        if (bias != 0.0F) {
            GL33.glSamplerParameterf(sampler, GL14.GL_TEXTURE_LOD_BIAS, bias);
        }
    }

    public static void unregister(int sampler) {
        samplers.remove(sampler);
    }

    /** Render thread. */
    public static void apply(float newBias) {
        if (Math.abs(newBias - bias) < 1e-4F) {
            return;
        }
        bias = newBias;
        for (int sampler : samplers) {
            GL33.glSamplerParameterf(sampler, GL14.GL_TEXTURE_LOD_BIAS, bias);
        }
        IrisDlss.LOGGER.info("Iris DLSS: texture LOD bias {} on {} samplers", String.format("%.3f", bias),
            samplers.size());
    }
}
