package com.irisdlss;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

/**
 * Runs DLSS on the world image: the world renders into a low-resolution main target with a
 * jittered projection; afterwards color, depth and camera-derived motion vectors are handed to
 * the Vulkan side through shared memory, and the upscaled result lands in the full-size target that
 * the GUI is drawn into.
 */
public final class DlssUpscaler {

    private static final int COLOR = 0;
    private static final int DEPTH = 1;
    private static final int MOTION = 2;
    private static final int OUTPUT = 3;
    private static final int[] GL_FORMATS = {GL11.GL_RGBA8, GL30.GL_R32F, GL30.GL_RG16F, GL11.GL_RGBA8};

    private static final String VERTEX_SHADER = """
        #version 330 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    // Camera-only motion vectors: reproject each pixel's position with last frame's camera.
    // Values are render-resolution pixels pointing from the current to the previous position.
    private static final String FRAGMENT_SHADER = """
        #version 330 core
        uniform sampler2D uDepth;
        uniform mat4 uInvCurrent;
        uniform mat4 uPrevious;
        uniform vec3 uCameraDelta;
        uniform vec2 uRenderSize;
        uniform float uHandDistance;
        uniform int uZeroToOne;
        uniform int uDepthInverted;
        layout(location = 0) out float outDepth;
        layout(location = 1) out vec2 outMotion;
        bool finite(vec2 v) {
            return !any(isnan(v)) && !any(isinf(v));
        }
        void main() {
            ivec2 pixel = ivec2(gl_FragCoord.xy);
            float depth = texelFetch(uDepth, pixel, 0).r;
            vec2 uv = (vec2(pixel) + 0.5) / uRenderSize;
            // Sky / far plane (and Voxy LODs drawn past it): no parallax, rotation-only reprojection.
            bool far = uDepthInverted != 0 ? depth <= 1e-6 : depth >= 1.0 - 1e-6;
            float sampleDepth = far ? (uDepthInverted != 0 ? 1e-6 : 1.0 - 1e-6) : depth;
            float ndcZ = uZeroToOne != 0 ? sampleDepth : sampleDepth * 2.0 - 1.0;
            vec4 position = uInvCurrent * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
            vec2 motion = vec2(0.0);
            if (abs(position.w) > 1e-8) {
                position.xyz /= position.w;
                if (length(position.xyz) > uHandDistance) {
                    vec3 offset = far ? vec3(0.0) : uCameraDelta;
                    vec4 previous = uPrevious * vec4(position.xyz + offset, 1.0);
                    if (previous.w > 1e-5) {
                        vec2 previousUv = previous.xy / previous.w * 0.5 + 0.5;
                        motion = (previousUv - uv) * uRenderSize;
                    }
                }
            }
            // NaN / inf poisons DLSS history (black smears); clamp to a sane range.
            if (!finite(motion)) {
                motion = vec2(0.0);
            }
            motion = clamp(motion, -uRenderSize, uRenderSize);
            outDepth = depth;
            outMotion = motion;
        }
        """;

    // Contrast-adaptive sharpening (after AMD FidelityFX CAS): restores the crispness of native
    // rendering that the temporal reconstruction softens; strength 0..1.
    private static final String SHARPEN_SHADER = """
        #version 330 core
        uniform sampler2D uColor;
        uniform float uSharpness;
        uniform ivec2 uSize;
        layout(location = 0) out vec4 outColor;
        vec3 at(ivec2 p) {
            return texelFetch(uColor, clamp(p, ivec2(0), uSize - 1), 0).rgb;
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
            vec3 weight = amount * (-1.0 / mix(8.0, 4.5, uSharpness));
            vec3 result = (c + (n + s + e + w) * weight) / (1.0 + 4.0 * weight);
            outColor = vec4(clamp(result, 0.0, 1.0), 1.0);
        }
        """;

    private static boolean initialized;
    private static boolean failed;

    private static int renderWidth;
    private static int renderHeight;
    private static int outputWidth;
    private static int outputHeight;
    private static boolean depthInverted;
    private static DlssConfig.Quality appliedQuality = DlssConfig.Quality.QUALITY;
    private static DlssConfig.Preset appliedPreset = DlssConfig.Preset.K;
    private static String failureReason = "";

    private static final int[] memoryObjects = new int[4];
    private static final int[] textures = new int[4];
    private static final long[] memoryHandles = new long[4];
    private static int glDoneSemaphore;
    private static int vkDoneSemaphore;
    private static long glDoneHandle;
    private static long vkDoneHandle;
    private static int motionFramebuffer;
    private static int readFramebuffer;
    private static int drawFramebuffer;
    private static int program;
    private static int sharpenProgram;
    private static int vertexArray;

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
        return textures[0] != 0;
    }

    /** Images Vulkan reads after the frame: depth, motion vectors, upscaled (hudless) output. */
    public static int[] sharedTextures() {
        return textures[0] == 0 ? new int[0] : new int[] {textures[DEPTH], textures[MOTION], textures[OUTPUT]};
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

    /** F3 line: world on OpenGL (Iris), upscaling on Vulkan. */
    public static String statusLine() {
        if (!DlssConfig.enabled) {
            return "DLSS: off";
        }
        if (failed) {
            return "DLSS: unavailable (" + failureReason + ")";
        }
        if (textures[0] == 0) {
            return "DLSS: starting";
        }
        return String.format("Vulkan DLSS SR %s, preset %s: %dx%d -> %dx%d, sharpen %d%%", appliedQuality.label,
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
        SamplerLodBias.apply(0.0F);
        IrisDlss.LOGGER.error("Iris DLSS disabled: {}", reason);
    }

    /** Render thread, OpenGL current. Sets up DLSS for this output size; false = run vanilla. */
    public static boolean prepare(int windowWidth, int windowHeight) {
        if (!DlssConfig.enabled && textures[0] != 0) {
            releaseResources();
            SamplerLodBias.apply(0.0F);
        }
        if (!isActive() || windowWidth <= 0 || windowHeight <= 0) {
            return false;
        }
        if (!initialized) {
            initialized = true;
            GLCapabilities caps = GL.getCapabilities();
            if (!caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_semaphore_win32 || !caps.GL_ARB_copy_image) {
                disable("OpenGL lacks GL_EXT_memory_object_win32 / GL_EXT_semaphore_win32 / copy_image");
                return false;
            }
            int status = DlssNative.init(deviceUuid(), IrisDlss.nativeDir.toAbsolutePath().toString());
            if (status != DlssNative.OK) {
                disable(DlssNative.message());
                return false;
            }
            IrisDlss.LOGGER.info("Iris DLSS: {}", DlssNative.message());
            program = createProgram(FRAGMENT_SHADER);
            sharpenProgram = createProgram(SHARPEN_SHADER);
            vertexArray = GL30.glGenVertexArrays();
            motionFramebuffer = GL30.glGenFramebuffers();
            readFramebuffer = GL30.glGenFramebuffers();
            drawFramebuffer = GL30.glGenFramebuffers();
        }
        boolean inverted = DepthConvention.depthInverted();
        if (windowWidth != outputWidth || windowHeight != outputHeight || textures[0] == 0
            || inverted != depthInverted || DlssConfig.quality != appliedQuality
            || DlssConfig.preset != appliedPreset) {
            int[] optimal = DlssNative.optimalSettings(windowWidth, windowHeight, DlssConfig.quality.ngx);
            if (optimal == null || optimal[0] <= 0 || optimal[1] <= 0) {
                disable("no render size for " + DlssConfig.quality.label);
                return false;
            }
            releaseResources();
            long[] resources = DlssNative.createResources(optimal[0], optimal[1], windowWidth, windowHeight,
                DlssConfig.quality.ngx, inverted, DlssConfig.preset.ngx);
            depthInverted = inverted;
            appliedQuality = DlssConfig.quality;
            appliedPreset = DlssConfig.preset;
            if (resources == null) {
                disable(DlssNative.message());
                return false;
            }
            importResources(resources);
            renderWidth = optimal[0];
            renderHeight = optimal[1];
            outputWidth = windowWidth;
            outputHeight = windowHeight;
            resetHistory = true;
            IrisDlss.LOGGER.info("Iris DLSS: {} {} preset {} (depth {}, clip control {})", DlssNative.message(),
                appliedQuality.label, appliedPreset.name(), inverted ? "reverse-Z" : "classic",
                DepthConvention.zeroToOne() ? "on" : "off");
        }
        // DLSS guideline: textures sampled with log2(render / output) LOD bias keep output-size detail.
        SamplerLodBias.apply(DlssConfig.textureLodBias
            ? Math.max(-1.0F, (float) (Math.log((double) renderWidth / outputWidth) / Math.log(2.0))) : 0.0F);
        return true;
    }

    private static byte[] deviceUuid() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uuid = stack.malloc(EXTMemoryObject.GL_UUID_SIZE_EXT);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, uuid);
            byte[] bytes = new byte[EXTMemoryObject.GL_UUID_SIZE_EXT];
            uuid.get(bytes);
            return bytes;
        }
    }

    private static void importResources(long[] r) {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        for (int i = 0; i < 4; i++) {
            memoryHandles[i] = r[i * 4];
            memoryObjects[i] = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameteriEXT(memoryObjects[i],
                EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11.GL_TRUE);
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoryObjects[i], r[i * 4 + 1],
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, memoryHandles[i]);
            textures[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT,
                EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
            EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL_FORMATS[i], (int) r[i * 4 + 2],
                (int) r[i * 4 + 3], memoryObjects[i], 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        glDoneHandle = r[16];
        vkDoneHandle = r[17];
        glDoneSemaphore = EXTSemaphore.glGenSemaphoresEXT();
        vkDoneSemaphore = EXTSemaphore.glGenSemaphoresEXT();
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(glDoneSemaphore,
            EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, glDoneHandle);
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(vkDoneSemaphore,
            EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, vkDoneHandle);

        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, motionFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textures[DEPTH], 0);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, textures[MOTION], 0);
        GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            disable("motion framebuffer incomplete 0x" + Integer.toHexString(status));
        }
    }

    private static void releaseResources() {
        if (textures[0] == 0) {
            return;
        }
        GL11.glFinish();
        for (int i = 0; i < 4; i++) {
            GL11.glDeleteTextures(textures[i]);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObjects[i]);
            DlssNative.closeHandle(memoryHandles[i]);
            textures[i] = 0;
            memoryObjects[i] = 0;
        }
        EXTSemaphore.glDeleteSemaphoresEXT(glDoneSemaphore);
        EXTSemaphore.glDeleteSemaphoresEXT(vkDoneSemaphore);
        DlssNative.closeHandle(glDoneHandle);
        DlssNative.closeHandle(vkDoneHandle);
        DlssNative.destroyResources();
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

    private static int glId(GpuTexture texture) {
        return texture instanceof GlTexture glTexture ? glTexture.glId() : 0;
    }

    /** Upscales low's color into full's color. Render thread, after the world is complete. */
    public static void process(RenderTarget low, RenderTarget full) {
        if (!isActive() || textures[0] == 0 || low.width != renderWidth || low.height != renderHeight) {
            return;
        }
        int lowColor = glId(low.getColorTexture());
        int lowDepth = glId(low.getDepthTexture());
        int fullColor = glId(full.getColorTexture());
        if (lowColor == 0 || lowDepth == 0 || fullColor == 0) {
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

        GlState state = GlState.capture();
        try {
            // Vulkan may still be reading last frame's images for presentation / frame generation.
            VulkanPresenter.awaitVulkan();
            GL43.glCopyImageSubData(lowColor, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, textures[COLOR], GL11.GL_TEXTURE_2D,
                0, 0, 0, 0, renderWidth, renderHeight, 1);

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, motionFramebuffer);
            GL11.glViewport(0, 0, renderWidth, renderHeight);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vertexArray);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lowDepth);
            GL33.glBindSampler(0, 0);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "uInvCurrent"), false,
                    new Matrix4f(currentViewProjection).invert().get(stack.mallocFloat(16)));
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "uPrevious"), false,
                    previousViewProjection.get(stack.mallocFloat(16)));
            }
            Vec3 delta = currentPosition.subtract(previousPosition);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "uCameraDelta"), (float) delta.x, (float) delta.y,
                (float) delta.z);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "uRenderSize"), renderWidth, renderHeight);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "uHandDistance"), DlssConfig.handDistance);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uDepth"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uZeroToOne"), DepthConvention.zeroToOne() ? 1 : 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uDepthInverted"), depthInverted ? 1 : 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            int[] layouts = {EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT,
                EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT};
            EXTSemaphore.glSignalSemaphoreEXT(glDoneSemaphore, new int[0], textures, layouts);
            GL11.glFlush();

            int status = DlssNative.evaluate(jitterX * DlssConfig.jitterSignX, jitterY * DlssConfig.jitterSignY,
                1.0F, 1.0F, resetHistory, 0.0F);
            if (status != DlssNative.OK) {
                disable(DlssNative.message());
                return;
            }
            EXTSemaphore.glWaitSemaphoreEXT(vkDoneSemaphore, new int[0], textures, layouts);

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                fullColor, 0);
            boolean sharpen = DlssConfig.sharpness > 0.001F && full.width == outputWidth
                && full.height == outputHeight;
            if (sharpen) {
                GL11.glViewport(0, 0, outputWidth, outputHeight);
                GL20.glUseProgram(sharpenProgram);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[OUTPUT]);
                GL20.glUniform1i(GL20.glGetUniformLocation(sharpenProgram, "uColor"), 0);
                GL20.glUniform1f(GL20.glGetUniformLocation(sharpenProgram, "uSharpness"),
                    Math.min(1.0F, DlssConfig.sharpness));
                GL20.glUniform2i(GL20.glGetUniformLocation(sharpenProgram, "uSize"), outputWidth, outputHeight);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                if (VulkanPresenter.isRunning() && DlssConfig.frameGeneration) {
                    // Frame generation compares the final frame with this "hudless" one to find the GUI.
                    GL43.glCopyImageSubData(fullColor, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, textures[OUTPUT],
                        GL11.GL_TEXTURE_2D, 0, 0, 0, 0, outputWidth, outputHeight, 1);
                }
            } else {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    textures[OUTPUT], 0);
                GL30.glBlitFramebuffer(0, 0, outputWidth, outputHeight, 0, 0, full.width, full.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            }
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        } finally {
            state.restore();
        }

        buildFrameGenParams(currentViewProjection, currentPosition.subtract(previousPosition));
        previousViewProjection.set(currentViewProjection);
        previousPosition = currentPosition;
        hasPrevious = true;
        resetHistory = false;
    }

    /**
     * DLSS-G camera description. Our images start at the bottom row while DLSS-G reads row 0 as the
     * top: flip clip y. Depth is mapped to [0, 1]. JOML's column-major arrays are exactly the
     * row-major, row-vector matrices NGX expects.
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

    private static int createProgram(String fragmentShader) {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fragmentShader);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Iris DLSS motion shader: " + GL20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Iris DLSS shader: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    /** OpenGL state the pass touches, restored so RenderPearl's GlStateManager caches stay valid. */
    private record GlState(int readFbo, int drawFbo, int program, int vao, int activeTexture, int texture0,
        int sampler0, int[] viewport, boolean depthTest, boolean blend, boolean cull, boolean scissor,
        boolean[] colorMask) {

        static GlState capture() {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int sampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            boolean[] mask = new boolean[4];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer m = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
                for (int i = 0; i < 4; i++) {
                    mask[i] = m.get(i) != 0;
                }
            }
            return new GlState(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), active, texture0, sampler0, viewport,
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glIsEnabled(GL11.GL_BLEND),
                GL11.glIsEnabled(GL11.GL_CULL_FACE), GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), mask);
        }

        void restore() {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0);
            GL33.glBindSampler(0, sampler0);
            GL13.glActiveTexture(activeTexture);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            set(GL11.GL_DEPTH_TEST, depthTest);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_SCISSOR_TEST, scissor);
            GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        }

        private static void set(int capability, boolean enabled) {
            if (enabled) {
                GL11.glEnable(capability);
            } else {
                GL11.glDisable(capability);
            }
        }
    }
}
