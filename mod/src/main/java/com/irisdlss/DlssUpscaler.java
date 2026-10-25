package com.irisdlss;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/**
 * Runs DLSS on the world image, entirely on the game's Vulkan device: the world renders into a
 * low-resolution main target with a jittered projection; afterwards a pass writes camera-derived
 * motion vectors, DLSS is recorded into the game's command buffer on the game's own color and depth
 * images, and the upscaled (and sharpened) result lands in the full-size target that the GUI is
 * drawn into.
 */
public final class DlssUpscaler {

    private static final String NAMESPACE = "iris-dlss";

    private static final String VERTEX_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        void main() {
            vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    // Camera-only motion vectors: reproject each pixel's position with last frame's camera.
    // Values are render-resolution pixels pointing from the current to the previous position.
    // DepthSampler is the world's depth (saved before the hand pass clears it), HandSampler the final
    // depth, where everything but the first-person hand is the clear value. Hand pixels get no
    // motion; DLSS and frame generation get the combined depth.
    private static final String MOTION_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        uniform sampler2D DepthSampler;
        uniform sampler2D HandSampler;
        layout(std140) uniform MotionInfo {
            mat4 InvCurrent;
            mat4 Previous;
            vec3 CameraDelta;
            float HandDistance;
            vec2 RenderSize;
            int ZeroToOne;
            int DepthInverted;
            int HandInDepth;
        };
        layout(location = 0) out vec2 outMotion;
        layout(location = 1) out float outDepth;
        bool finite(vec2 v) {
            return !any(isnan(v)) && !any(isinf(v));
        }
        void main() {
            ivec2 pixel = ivec2(gl_FragCoord.xy);
            float depth = texelFetch(DepthSampler, pixel, 0).r;
            float handDepth = texelFetch(HandSampler, pixel, 0).r;
            bool hand = HandInDepth != 0 && (DepthInverted != 0 ? handDepth > 0.0 : handDepth < 1.0);
            outDepth = hand ? handDepth : depth;
            if (hand) {
                outMotion = vec2(0.0);
                return;
            }
            vec2 uv = (vec2(pixel) + 0.5) / RenderSize;
            // Sky / far plane (and Voxy LODs drawn past it): no parallax, rotation-only reprojection.
            bool far = DepthInverted != 0 ? depth <= 1e-6 : depth >= 1.0 - 1e-6;
            float sampleDepth = far ? (DepthInverted != 0 ? 1e-6 : 1.0 - 1e-6) : depth;
            float ndcZ = ZeroToOne != 0 ? sampleDepth : sampleDepth * 2.0 - 1.0;
            vec4 position = InvCurrent * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
            vec2 motion = vec2(0.0);
            if (abs(position.w) > 1e-8) {
                position.xyz /= position.w;
                if (length(position.xyz) > HandDistance) {
                    vec3 offset = far ? vec3(0.0) : CameraDelta;
                    vec4 previous = Previous * vec4(position.xyz + offset, 1.0);
                    if (previous.w > 1e-5) {
                        vec2 previousUv = previous.xy / previous.w * 0.5 + 0.5;
                        motion = (previousUv - uv) * RenderSize;
                    }
                }
            }
            // NaN / inf poisons DLSS history (black smears); clamp to a sane range.
            if (!finite(motion)) {
                motion = vec2(0.0);
            }
            outMotion = clamp(motion, -RenderSize, RenderSize);
        }
        """;

    // Contrast-adaptive sharpening (after AMD FidelityFX CAS): restores the crispness of native
    // rendering that the temporal reconstruction softens; strength 0..1.
    private static final String SHARPEN_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        uniform sampler2D ColorSampler;
        layout(std140) uniform SharpenInfo {
            float Sharpness;
            ivec2 Size;
        };
        layout(location = 0) out vec4 fragColor;
        vec3 at(ivec2 p) {
            return texelFetch(ColorSampler, clamp(p, ivec2(0), Size - 1), 0).rgb;
        }
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 c = at(p);
            vec3 n = at(p + ivec2(0, 1));
            vec3 s = at(p - ivec2(0, 1));
            vec3 e = at(p + ivec2(1, 0));
            vec3 w = at(p - ivec2(1, 0));
            vec3 lo = min(c, min(min(n, s), min(e, w)));
            vec3 hi = max(c, max(max(n, s), max(e, w)));
            vec3 amount = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, vec3(1e-4)), 0.0, 1.0));
            vec3 weight = amount * (-1.0 / mix(8.0, 4.5, Sharpness));
            vec3 result = (c + (n + s + e + w) * weight) / (1.0 + 4.0 * weight);
            fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
        }
        """;

    /** Our shaders, handed straight to RenderPearl's GLSL -> SPIR-V compiler. */
    private static final ShaderSource SHADERS = new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            return switch (id.toString()) {
                case NAMESPACE + ":fullscreen" -> VERTEX_SHADER;
                case NAMESPACE + ":motion_vectors" -> MOTION_SHADER;
                case NAMESPACE + ":sharpen" -> SHARPEN_SHADER;
                default -> null;
            };
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return null;
        }

        @Override
        public void close() {
        }
    };

    private static final RenderPipeline MOTION_PIPELINE = fullscreenPipeline("motion_vectors",
        BindGroupLayout.builder().withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("HandSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("MotionInfo", UniformType.UNIFORM_BUFFER).build(),
        new ColorTargetState(Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_ALL),
        new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL));
    private static final RenderPipeline SHARPEN_PIPELINE = fullscreenPipeline("sharpen",
        BindGroupLayout.builder().withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SharpenInfo", UniformType.UNIFORM_BUFFER).build(),
        ColorTargetState.DEFAULT);
    private static final int MOTION_INFO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putVec3().putFloat()
        .putVec2().putInt().putInt().putInt().get();
    private static final int SHARPEN_INFO_SIZE = new Std140SizeCalculator().putFloat().putIVec2().get();

    /** Dev option -Diris-dlss.debugLog=true: camera inputs of every upscaled frame. */
    private static final boolean DEBUG_LOG = Boolean.getBoolean("iris-dlss.debugLog");

    private static boolean initialized;
    private static boolean failed;

    private static int renderWidth;
    private static int renderHeight;
    private static int outputWidth;
    private static int outputHeight;
    private static boolean depthInverted;
    private static DlssConfig.Quality appliedQuality = DlssConfig.Quality.QUALITY;
    private static DlssConfig.Preset appliedPreset = DlssConfig.Preset.K;
    private static int appliedRenderScale;
    private static String failureReason = "";

    private static CompiledRenderPipeline motionPipeline;
    private static CompiledRenderPipeline sharpenPipeline;
    private static GpuTexture motionTexture;
    private static GpuTextureView motionView;
    private static GpuTexture outputTexture;
    private static GpuTextureView outputView;
    /** The world's depth, copied before the hand pass clears the main depth buffer. */
    private static GpuTexture worldDepthTexture;
    private static GpuTextureView worldDepthView;
    private static boolean worldDepthSaved;
    /** The main depth buffer holds the hand (drawn after clearing it) instead of the world. */
    private static boolean handInDepth;
    /** World depth with the hand on top (R32F), the depth DLSS and frame generation see. */
    private static GpuTexture combinedDepthTexture;
    private static GpuTextureView combinedDepthView;

    private static int jitterIndex;
    private static float jitterX;
    private static float jitterY;
    private static boolean jitterApplied;
    private static final Matrix4f currentProjection = new Matrix4f();
    private static final Matrix4f currentView = new Matrix4f();
    private static final Matrix4f previousViewProjection = new Matrix4f();
    private static Vec3 currentPosition = Vec3.ZERO;
    private static Vec3 previousPosition = Vec3.ZERO;
    private static boolean hasPrevious;
    private static boolean resetHistory = true;
    private static float depthFar = 1024.0F;
    private static Object lastLevel;
    private static int resetFrames;

    // Frame generation inputs of the last processed frame (see bridge.h IRISDLSS_FG_*).
    private static final float[] frameGenParams = new float[DlssNative.FG_PARAM_COUNT];
    private static boolean frameGenParamsReady;
    private static boolean resetFrameGen = true;

    private DlssUpscaler() {
    }

    private static RenderPipeline fullscreenPipeline(String name, BindGroupLayout layout, ColorTargetState... targets) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/" + name))
            .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "fullscreen"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, name))
            .withBindGroupLayout(layout)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false);
        for (int i = 0; i < targets.length; i++) {
            builder.withColorTargetState(i, targets[i]);
        }
        return builder.build();
    }

    public static boolean isActive() {
        return DlssConfig.enabled && !failed;
    }

    public static int renderWidth() {
        return renderWidth;
    }

    public static int renderHeight() {
        return renderHeight;
    }

    public static void requestReset() {
        resetHistory = true;
    }

    public static boolean hasResources() {
        return outputTexture != null;
    }

    /** Frame generation inputs of the last frame: depth, motion vectors, hudless output; or null. */
    public static long[] frameGenImages() {
        if (outputView == null) {
            return null;
        }
        return VulkanAccess.concat(VulkanAccess.describe(combinedDepthView), VulkanAccess.describe(motionView),
            VulkanAccess.describe(outputView));
    }

    /** This frame's frame generation inputs, or null (no world this frame, or a menu is open). */
    public static float[] takeFrameGenParams() {
        boolean ready = frameGenParamsReady;
        frameGenParamsReady = false;
        if (!ready || Minecraft.getInstance().gui.screen() != null) {
            resetFrameGen = true;
            return null;
        }
        if (resetFrameGen) {
            frameGenParams[84] = 1.0F;
            resetFrameGen = false;
        }
        return frameGenParams;
    }

    /** F3 line. */
    public static String statusLine() {
        if (!DlssConfig.enabled) {
            return "DLSS: off";
        }
        if (failed) {
            return "DLSS: unavailable (" + failureReason + ")";
        }
        if (outputTexture == null) {
            return "DLSS: starting";
        }
        return String.format("Vulkan DLSS SR %s, preset %s: %dx%d -> %dx%d, sharpen %d%%", modeLabel(),
            appliedPreset.name(), renderWidth, renderHeight, outputWidth, outputHeight,
            Math.round(DlssConfig.sharpness * 100));
    }

    public static boolean hasFailed() {
        return failed;
    }

    public static String failureReason() {
        return failureReason;
    }

    private static void disable(String reason) {
        failed = true;
        failureReason = reason;
        IrisDlss.LOGGER.error("Iris DLSS disabled: {}", reason);
    }

    /** Render thread, start of the frame. Sets up DLSS for this output size; false = run vanilla. */
    public static boolean prepare(int windowWidth, int windowHeight) {
        if (!DlssConfig.enabled && outputTexture != null) {
            releaseResources();
        }
        if (!isActive() || windowWidth <= 0 || windowHeight <= 0) {
            return false;
        }
        if (!initialized) {
            initialized = true;
            if (!VulkanAccess.isVulkan()) {
                disable("needs the Vulkan graphics API (Video Settings > Graphics API)");
                return false;
            }
            int status = DlssNative.init();
            if (status != DlssNative.OK) {
                disable(DlssNative.message());
                return false;
            }
            IrisDlss.LOGGER.info("Iris DLSS: {}", DlssNative.message());
            motionPipeline = compile(MOTION_PIPELINE);
            sharpenPipeline = compile(SHARPEN_PIPELINE);
            if (motionPipeline == null || sharpenPipeline == null) {
                disable("could not compile the motion vector / sharpening shaders");
                return false;
            }
        }
        boolean inverted = DepthConvention.depthInverted();
        if (windowWidth != outputWidth || windowHeight != outputHeight || outputTexture == null
            || inverted != depthInverted || DlssConfig.quality != appliedQuality
            || DlssConfig.preset != appliedPreset
            || DlssConfig.quality == DlssConfig.Quality.CUSTOM && DlssConfig.renderScale != appliedRenderScale) {
            int[] optimal = renderSize(windowWidth, windowHeight);
            if (optimal == null || optimal[0] <= 0 || optimal[1] <= 0) {
                disable("no render size for " + DlssConfig.quality.label);
                return false;
            }
            releaseResources();
            depthInverted = inverted;
            appliedQuality = DlssConfig.quality;
            appliedPreset = DlssConfig.preset;
            appliedRenderScale = DlssConfig.renderScale;
            int status = DlssNative.createResources(VulkanAccess.commandBuffer(), optimal[0], optimal[1], windowWidth,
                windowHeight, DlssConfig.quality.ngxMode(), inverted, DlssConfig.preset.ngx);
            if (status != DlssNative.OK) {
                disable(DlssNative.message());
                return false;
            }
            renderWidth = optimal[0];
            renderHeight = optimal[1];
            outputWidth = windowWidth;
            outputHeight = windowHeight;
            motionTexture = RenderSystem.getDevice().createTexture("DLSS motion vectors",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RG16_FLOAT, renderWidth, renderHeight, 1, 1);
            motionView = RenderSystem.getDevice().createTextureView(motionTexture);
            worldDepthTexture = RenderSystem.getDevice().createTexture("DLSS world depth",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT, renderWidth,
                renderHeight, 1, 1);
            worldDepthView = RenderSystem.getDevice().createTextureView(worldDepthTexture);
            combinedDepthTexture = RenderSystem.getDevice().createTexture("DLSS depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.R32_FLOAT, renderWidth, renderHeight, 1, 1);
            combinedDepthView = RenderSystem.getDevice().createTextureView(combinedDepthTexture);
            outputTexture = VulkanAccess.createStorageTexture("DLSS output", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, outputWidth, outputHeight);
            outputView = RenderSystem.getDevice().createTextureView(outputTexture);
            resetHistory = true;
            IrisDlss.LOGGER.info("Iris DLSS: {} {} preset {} (depth {}, texture LOD bias {})", DlssNative.message(),
                modeLabel(), appliedPreset.name(), inverted ? "reverse-Z" : "classic",
                String.format("%.2f", SamplerLodBias.vulkanBias()));
        }
        return true;
    }

    /** Render size for the window: NVIDIA's optimal size for the mode, or the custom scale. */
    private static int[] renderSize(int windowWidth, int windowHeight) {
        if (DlssConfig.quality != DlssConfig.Quality.CUSTOM) {
            return DlssNative.optimalSettings(windowWidth, windowHeight, DlssConfig.quality.ngx);
        }
        double scale = DlssConfig.quality.scale();
        return new int[] {Math.max(1, (int) Math.round(windowWidth * scale)),
            Math.max(1, (int) Math.round(windowHeight * scale))};
    }

    /** "Quality" or "Custom 80 %". */
    public static String modeLabel() {
        return appliedQuality == DlssConfig.Quality.CUSTOM ? "Custom " + appliedRenderScale + " %" : appliedQuality.label;
    }

    private static CompiledRenderPipeline compile(RenderPipeline pipeline) {
        try {
            return RenderSystem.getDevice().compilePipeline(pipeline, SHADERS, Runnable::run).join().finishCompile();
        } catch (RuntimeException e) {
            IrisDlss.LOGGER.error("Iris DLSS: pipeline {} failed", pipeline.getLocation(), e);
            return null;
        }
    }

    private static void releaseResources() {
        if (outputTexture == null) {
            return;
        }
        // RenderPearl destroys textures once no frame in flight uses them; the bridge does the same
        // for the DLSS feature.
        DlssNative.destroyResources();
        motionView.close();
        motionTexture.close();
        worldDepthView.close();
        worldDepthTexture.close();
        combinedDepthView.close();
        combinedDepthTexture.close();
        worldDepthView = null;
        worldDepthTexture = null;
        combinedDepthView = null;
        combinedDepthTexture = null;
        outputView.close();
        outputTexture.close();
        motionView = null;
        motionTexture = null;
        outputView = null;
        outputTexture = null;
    }

    /**
     * GameRenderer.render3dHud, before the hand: keep the world's depth. Without post effects the hand
     * pass clears the main depth buffer and draws only the hand into it.
     */
    public static void saveWorldDepth(RenderTarget low, boolean handInOwnTarget) {
        if (!isActive() || worldDepthTexture == null || low.width != renderWidth || low.height != renderHeight
            || low.getDepthTexture() == null) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(low.getDepthTexture(), worldDepthTexture,
            0, 0, 0, 0, 0, renderWidth, renderHeight);
        worldDepthSaved = true;
        handInDepth = !handInOwnTarget;
    }

    /** The game's Vulkan device is being destroyed (it already waited for the GPU). */
    public static void shutdown() {
        if (!IrisDlss.isNativeLoaded()) {
            return;
        }
        VulkanPresenter.stop();
        DlssNative.shutdown();
        initialized = false;
        failed = true;
        failureReason = "shut down";
    }

    /** Camera.extractRenderState: jitter the projection by a sub-pixel Halton offset. */
    public static void applyJitter(CameraRenderState camera) {
        jitterApplied = false;
        if (!isActive() || renderWidth <= 0) {
            return;
        }
        float scale = (float) outputWidth / renderWidth;
        int phases = Math.max(8, (int) Math.ceil(8.0 * scale * scale));
        jitterIndex = (jitterIndex + 1) % phases;
        jitterX = halton(jitterIndex + 1, 2) - 0.5F;
        jitterY = halton(jitterIndex + 1, 3) - 0.5F;
        currentProjection.set(camera.projectionMatrix);
        camera.projectionMatrix.set(new Matrix4f()
            .translation(2.0F * jitterX / renderWidth, 2.0F * jitterY / renderHeight, 0.0F)
            .mul(currentProjection));
        currentView.set(camera.viewRotationMatrix);
        currentPosition = camera.pos;
        depthFar = camera.depthFar;
        jitterApplied = true;
    }

    /** GameRenderer.renderLevel: the projection actually used, incl. view bobbing (still jittered). */
    public static void captureProjection(Matrix4f jitteredProjection) {
        if (!jitterApplied) {
            return;
        }
        currentProjection.set(new Matrix4f()
            .translation(-2.0F * jitterX / renderWidth, -2.0F * jitterY / renderHeight, 0.0F)
            .mul(jitteredProjection));
    }

    private static float halton(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F;
        while (index > 0) {
            fraction /= base;
            result += fraction * (index % base);
            index /= base;
        }
        return result;
    }

    /** Upscales low's color into full's color. Render thread, after the world is complete. */
    public static void process(RenderTarget low, RenderTarget full) {
        if (!isActive() || outputTexture == null || low.width != renderWidth || low.height != renderHeight) {
            return;
        }
        GpuTextureView lowColor = low.getColorTextureView();
        GpuTextureView lowDepth = low.getDepthTextureView();
        GpuTexture fullColor = full.getColorTexture();
        GpuTextureView fullColorView = full.getColorTextureView();
        if (lowColor == null || lowDepth == null || fullColor == null || fullColorView == null) {
            return;
        }

        Matrix4f currentViewProjection = DepthConvention.projectionForDepth(currentProjection).mul(currentView);
        // New world / dimension, or still loading in: old history would show through walls.
        Object level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            lastLevel = level;
            resetFrames = 60;
        }
        if (resetFrames > 0) {
            resetFrames--;
            resetHistory = true;
        }
        if (hasPrevious && currentPosition.distanceToSqr(previousPosition) > 16.0 * 16.0) {
            resetHistory = true;
        }
        if (!hasPrevious || resetHistory) {
            previousViewProjection.set(currentViewProjection);
            previousPosition = currentPosition;
        }

        if (DEBUG_LOG) {
            Vec3 d = currentPosition.subtract(previousPosition);
            IrisDlss.LOGGER.info("Iris DLSS debug: delta {} {} {} jitter {} {} reset {} proj m00 {} m11 {} m20 {} m21 {} "
                + "view m00 {} m02 {} prev m00 {} m20 {}", d.x, d.y, d.z, jitterX, jitterY, resetHistory,
                currentProjection.m00(), currentProjection.m11(), currentProjection.m20(), currentProjection.m21(),
                currentView.m00(), currentView.m02(), previousViewProjection.m00(), previousViewProjection.m20());
            IrisDlss.LOGGER.info("Iris DLSS debug: projectionForDepth {} view {} zeroToOne {} inverted {}",
                java.util.Arrays.toString(DepthConvention.projectionForDepth(currentProjection).get(new float[16])),
                java.util.Arrays.toString(currentView.get(new float[16])), DepthConvention.zeroToOne(), depthInverted);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (!worldDepthSaved) {
            // No hand pass this frame: the main depth buffer still is the world's.
            encoder.copyTextureToTexture(low.getDepthTexture(), worldDepthTexture, 0, 0, 0, 0, 0, renderWidth,
                renderHeight);
            handInDepth = false;
        }
        worldDepthSaved = false;
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        Vec3 delta = currentPosition.subtract(previousPosition);
        GpuBufferSlice motionInfo;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, MOTION_INFO_SIZE)
                .putMat4f(new Matrix4f(currentViewProjection).invert())
                .putMat4f(previousViewProjection)
                .putVec3((float) delta.x, (float) delta.y, (float) delta.z)
                .putFloat(DlssConfig.handDistance)
                .putVec2(renderWidth, renderHeight)
                .putInt(DepthConvention.zeroToOne() ? 1 : 0)
                .putInt(depthInverted ? 1 : 0)
                .putInt(handInDepth ? 1 : 0)
                .get();
            motionInfo = encoder.transientMemory().uploadGpu(data, alignment, GpuBuffer.USAGE_UNIFORM);
        }
        RenderPassDescriptor motionPass = RenderPassDescriptor.builder(() -> "DLSS motion vectors")
            .withColorAttachment(motionView, Optional.empty())
            .withColorAttachment(combinedDepthView, Optional.empty())
            .build();
        try (RenderPass pass = encoder.createRenderPass(motionPass)) {
            pass.setPipeline(motionPipeline);
            pass.setUniform("DepthSampler", worldDepthView, nearest);
            pass.setUniform("HandSampler", lowDepth, nearest);
            pass.setUniform("MotionInfo", motionInfo);
            pass.draw(3, 1, 0, 0);
        }

        int status = DlssNative.evaluate(VulkanAccess.commandBuffer(),
            VulkanAccess.concat(VulkanAccess.describe(lowColor), VulkanAccess.describe(combinedDepthView),
                VulkanAccess.describe(motionView), VulkanAccess.describe(outputView)),
            jitterX * DlssConfig.jitterSignX, jitterY * DlssConfig.jitterSignY, 1.0F, 1.0F, resetHistory);
        if (status != DlssNative.OK) {
            disable(DlssNative.message());
            return;
        }

        if (full.width == outputWidth && full.height == outputHeight) {
            if (DlssConfig.sharpness > 0.001F) {
                GpuBufferSlice sharpenInfo;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer data = Std140Builder.onStack(stack, SHARPEN_INFO_SIZE)
                        .putFloat(Math.min(1.0F, DlssConfig.sharpness))
                        .putIVec2(outputWidth, outputHeight)
                        .get();
                    sharpenInfo = encoder.transientMemory().uploadGpu(data, alignment, GpuBuffer.USAGE_UNIFORM);
                }
                try (RenderPass pass = encoder.createRenderPass(() -> "DLSS sharpen", fullColorView, Optional.empty())) {
                    pass.setPipeline(sharpenPipeline);
                    pass.setUniform("ColorSampler", outputView, nearest);
                    pass.setUniform("SharpenInfo", sharpenInfo);
                    pass.draw(3, 1, 0, 0);
                }
                if (VulkanPresenter.isRunning() && DlssConfig.frameGeneration) {
                    // Frame generation compares the final frame with this "hudless" one to find the GUI.
                    encoder.copyTextureToTexture(fullColor, outputTexture, 0, 0, 0, 0, 0, outputWidth, outputHeight);
                }
            } else {
                encoder.copyTextureToTexture(outputTexture, fullColor, 0, 0, 0, 0, 0, outputWidth, outputHeight);
            }
        }

        buildFrameGenParams(currentViewProjection, delta);
        previousViewProjection.set(currentViewProjection);
        previousPosition = currentPosition;
        hasPrevious = true;
        resetHistory = false;
    }

    /**
     * DLSS-G camera description. The game's images start at the bottom row (RenderPearl keeps
     * OpenGL's orientation on Vulkan and flips when it presents) while DLSS-G reads row 0 as the top:
     * flip clip y. Depth is mapped to [0, 1]. JOML's column-major arrays are exactly the row-major,
     * row-vector matrices NGX expects.
     */
    private static void buildFrameGenParams(Matrix4f currentViewProjection, Vec3 delta) {
        float[] p = frameGenParams;
        Matrix4f fix = new Matrix4f().scaling(1.0F, -1.0F, 1.0F);
        if (!DepthConvention.zeroToOne()) {
            fix.mul(new Matrix4f().translation(0.0F, 0.0F, 0.5F).scale(1.0F, 1.0F, 0.5F));
        }
        Matrix4f fixInverse = new Matrix4f(fix).invert();
        Matrix4f viewToClip = new Matrix4f(fix).mul(DepthConvention.projectionForDepth(currentProjection));
        viewToClip.get(p, 0);
        new Matrix4f(viewToClip).invert().get(p, 16);
        Matrix4f clipToPrevClip = new Matrix4f(fix).mul(previousViewProjection)
            .translate((float) delta.x, (float) delta.y, (float) delta.z)
            .mul(new Matrix4f(currentViewProjection).invert()).mul(fixInverse);
        clipToPrevClip.get(p, 32);
        new Matrix4f(clipToPrevClip).invert().get(p, 48);
        p[64] = jitterX * DlssConfig.jitterSignX;
        p[65] = jitterY * DlssConfig.jitterSignY;
        p[66] = 1.0F / renderWidth;
        p[67] = 1.0F / renderHeight;
        p[68] = (float) currentPosition.x;
        p[69] = (float) currentPosition.y;
        p[70] = (float) currentPosition.z;
        Matrix4f viewToWorld = new Matrix4f(currentView).invert();
        Vector3f up = viewToWorld.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f right = viewToWorld.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f forward = viewToWorld.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F));
        p[71] = up.x;
        p[72] = up.y;
        p[73] = up.z;
        p[74] = right.x;
        p[75] = right.y;
        p[76] = right.z;
        p[77] = forward.x;
        p[78] = forward.y;
        p[79] = forward.z;
        p[80] = 0.05F;
        p[81] = depthFar;
        p[82] = 2.0F * (float) Math.atan(1.0 / Math.abs(currentProjection.m11()));
        p[83] = Math.abs(currentProjection.m11() / currentProjection.m00());
        p[84] = resetHistory ? 1.0F : 0.0F;
        p[85] = depthInverted ? 1.0F : 0.0F;
        frameGenParamsReady = true;
    }
}
