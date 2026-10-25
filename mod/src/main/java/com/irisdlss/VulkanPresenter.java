package com.irisdlss;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * Replaces the OpenGL swap: the finished frame (GUI included) is copied into memory shared with
 * Vulkan and shown through a Vulkan swapchain, where DLSS Frame Generation can add the frame in
 * between. Hooked into GlSurface.blitFromTexture / present.
 */
public final class VulkanPresenter {

    private static final String SDL_PROP_WINDOW_WIN32_HWND_POINTER = "SDL.window.win32.hwnd";

    private static boolean running;
    private static boolean failed;
    private static String failureReason = "";
    private static int width;
    private static int height;
    private static boolean vsync = true;

    private static int memoryObject;
    private static int frameTexture;
    private static long memoryHandle;
    private static int readySemaphore;
    private static int consumedSemaphore;
    private static long readyHandle;
    private static long consumedHandle;
    /** Vulkan signaled `consumed` for the last frame and OpenGL has not waited on it yet. */
    private static boolean pendingConsumed;
    private static boolean presentedThisFrame;
    private static float[] stats = new float[3];
    private static long lastStatsTime;

    private VulkanPresenter() {
    }

    public static boolean isRunning() {
        return running;
    }

    private static boolean wanted() {
        return DlssConfig.vulkanPresent && !failed && DlssUpscaler.isActive() && DlssUpscaler.hasResources();
    }

    /** GlSurface.configure: the game's VSync setting (FIFO = on). */
    public static void setVsync(boolean enabled) {
        vsync = enabled;
        if (running) {
            DlssNative.setVsync(enabled);
        }
    }

    /**
     * Before OpenGL writes anything Vulkan still reads (DLSS inputs, the frame image): wait for the
     * previous frame's presentation work to have taken its copies.
     */
    public static void awaitVulkan() {
        if (!pendingConsumed) {
            return;
        }
        pendingConsumed = false;
        int[] textures = sharedTextures();
        EXTSemaphore.glWaitSemaphoreEXT(consumedSemaphore, new int[0], textures, generalLayouts(textures.length));
    }

    /** GlSurface.blitFromTexture. True if the frame went to Vulkan (skip the OpenGL blit and swap). */
    public static boolean present(long sdlWindow, GpuTextureView view) {
        presentedThisFrame = false;
        if (!wanted()) {
            stop();
            return false;
        }
        if (!(view.texture() instanceof GlTexture source)) {
            return false;
        }
        int w = view.getWidth(0);
        int h = view.getHeight(0);
        // Frame Generation toggled: rebuild the presenter from scratch instead of switching live.
        if (running && DlssConfig.frameGeneration != appliedFrameGeneration) {
            stop();
        }
        if (!running || w != width || h != height) {
            appliedFrameGeneration = DlssConfig.frameGeneration;
            if (!start(sdlWindow, w, h)) {
                return false;
            }
        }
        awaitVulkan();
        GL43.glCopyImageSubData(source.glId(), GL11.GL_TEXTURE_2D, 0, 0, 0, 0, frameTexture, GL11.GL_TEXTURE_2D, 0,
            0, 0, 0, w, h, 1);
        int[] textures = sharedTextures();
        EXTSemaphore.glSignalSemaphoreEXT(readySemaphore, new int[0], textures, generalLayouts(textures.length));
        GL11.glFlush();

        float[] fg = DlssConfig.frameGeneration ? DlssUpscaler.takeFrameGenParams() : null;
        int status = DlssNative.present(fg);
        pendingConsumed = status != DlssNative.DEVICE_FAILED;
        if (status == DlssNative.DEVICE_FAILED) {
            disable(DlssNative.message());
            return false;
        }
        if (status != DlssNative.OK && fg != null) {
            IrisDlss.LOGGER.warn("Iris DLSS: frame generation off: {}", DlssNative.message());
        }
        presentedThisFrame = true;
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 10_000) {
            lastLogTime = now;
            IrisDlss.LOGGER.info("Iris DLSS: {} | {}", statusLine(), DlssUpscaler.statusLine());
        }
        return true;
    }

    private static long lastLogTime;
    private static boolean appliedFrameGeneration;

    /** GlSurface.present: skip the OpenGL swap when Vulkan showed the frame. */
    public static boolean consumeSwap() {
        boolean skip = presentedThisFrame;
        presentedThisFrame = false;
        return skip;
    }

    private static boolean start(long sdlWindow, int w, int h) {
        stop();
        long hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow),
            SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        if (hwnd == 0L) {
            disable("no Win32 window handle");
            return false;
        }
        long[] r = DlssNative.createPresenter(hwnd, w, h, vsync);
        if (r == null) {
            disable(DlssNative.message());
            return false;
        }
        memoryHandle = r[0];
        memoryObject = EXTMemoryObject.glCreateMemoryObjectsEXT();
        EXTMemoryObject.glMemoryObjectParameteriEXT(memoryObject, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT,
            GL11.GL_TRUE);
        EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoryObject, r[1],
            EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, memoryHandle);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        frameTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, frameTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT,
            EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
        EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, (int) r[2], (int) r[3],
            memoryObject, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        readyHandle = r[4];
        consumedHandle = r[5];
        readySemaphore = EXTSemaphore.glGenSemaphoresEXT();
        consumedSemaphore = EXTSemaphore.glGenSemaphoresEXT();
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(readySemaphore,
            EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, readyHandle);
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(consumedSemaphore,
            EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, consumedHandle);
        width = w;
        height = h;
        running = true;
        pendingConsumed = false;
        DlssUpscaler.requestReset();
        IrisDlss.LOGGER.info("Iris DLSS: {}", DlssNative.message());
        return true;
    }

    /** Back to the OpenGL swap (DLSS off, setting off, or before recreating). */
    public static void stop() {
        if (!running) {
            return;
        }
        running = false;
        pendingConsumed = false;
        GL11.glFinish();
        DlssNative.destroyPresenter();
        GL11.glDeleteTextures(frameTexture);
        EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObject);
        EXTSemaphore.glDeleteSemaphoresEXT(readySemaphore);
        EXTSemaphore.glDeleteSemaphoresEXT(consumedSemaphore);
        DlssNative.closeHandle(memoryHandle);
        DlssNative.closeHandle(readyHandle);
        DlssNative.closeHandle(consumedHandle);
        frameTexture = 0;
        memoryObject = 0;
    }

    private static void disable(String reason) {
        stop();
        failed = true;
        failureReason = reason;
        IrisDlss.LOGGER.error("Iris DLSS: Vulkan presentation off, back to OpenGL: {}", reason);
    }

    /** Settings changed: try again (e.g. after a failure). */
    public static void retry() {
        failed = false;
        failureReason = "";
    }

    private static int[] sharedTextures() {
        int[] dlss = DlssUpscaler.sharedTextures();
        int[] all = new int[dlss.length + 1];
        System.arraycopy(dlss, 0, all, 0, dlss.length);
        all[dlss.length] = frameTexture;
        return all;
    }

    private static int[] generalLayouts(int count) {
        int[] layouts = new int[count];
        java.util.Arrays.fill(layouts, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
        return layouts;
    }

    /** F3 / settings line. */
    public static String statusLine() {
        if (!DlssConfig.vulkanPresent) {
            return "Output: OpenGL swap";
        }
        if (failed) {
            return "Output: OpenGL (Vulkan failed: " + failureReason + ")";
        }
        if (!running) {
            return "Output: Vulkan (starting)";
        }
        long now = System.currentTimeMillis();
        if (now - lastStatsTime > 500) {
            stats = DlssNative.presenterStats();
            lastStatsTime = now;
        }
        String fg = !DlssConfig.frameGeneration ? "Frame Generation off"
            : stats[1] > 0 ? "Frame Generation x2" : "Frame Generation idle";
        return String.format("Output: Vulkan swapchain %s, %.0f fps shown, %s", vsync ? "VSync" : "no VSync",
            stats[0], fg);
    }
}
