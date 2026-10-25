package com.irisdlss;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * Takes over the game's swapchain output: the finished frame (GUI included) is copied, inside the
 * game's own command buffer, into the bridge's presenter, which shows it through a swapchain on a
 * child window where DLSS Frame Generation can add the frame in between. Hooked into
 * VulkanGpuSurface (see VulkanGpuSurfaceMixin).
 */
public final class VulkanPresenter {

    private static final String SDL_PROP_WINDOW_WIN32_HWND_POINTER = "SDL.window.win32.hwnd";
    private static final long VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT = 0x10000L;

    private static boolean running;
    private static boolean failed;
    private static String failureReason = "";
    private static int width;
    private static int height;
    private static boolean vsync = true;
    private static long timeline;
    private static float[] stats = new float[8];
    private static long lastStatsTime;
    private static long lastLogTime;
    private static boolean appliedFrameGeneration;
    /** Dev option -Diris-dlss.captureAt=N: capture frames after N frame-generated presents. */
    private static final int CAPTURE_AT = Integer.getInteger("iris-dlss.captureAt", -1);
    private static int frameGeneratedPresents;
    /** Another mod or client acquires the swapchain images itself (see foreignSwapchain). */
    private static boolean foreignSwapchain;
    private static int foreignFrames;

    private VulkanPresenter() {
    }

    public static boolean isRunning() {
        return running;
    }

    /** The presenter should show this frame (decided before the game acquires its swapchain image). */
    public static boolean wanted() {
        return DlssConfig.vulkanPresent && !failed && DlssUpscaler.isActive() && DlssUpscaler.hasResources();
    }

    /** VulkanGpuSurface.configure: the game's VSync setting (FIFO = on). */
    public static void setVsync(boolean enabled) {
        vsync = enabled;
        if (running) {
            DlssNative.setVsync(enabled);
        }
    }

    /**
     * Minecraft.runTick, before input is read: like NVIDIA Reflex, start the frame only when the
     * presenter is about to need it, so frames do not queue up behind the display.
     */
    public static void frameStart() {
        if (running) {
            DlssNative.frameStart();
        }
    }

    /** VulkanGpuSurface.blitFromTexture. True if the frame went to the presenter. */
    public static boolean present(long sdlWindow, GpuTextureView view) {
        if (!wanted()) {
            stop();
            return false;
        }
        if (!(view.texture() instanceof VulkanGpuTexture)) {
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
        long[] fgImages = null;
        float[] fg = null;
        if (DlssConfig.frameGeneration) {
            fg = DlssUpscaler.takeFrameGenParams();
            fgImages = fg != null ? DlssUpscaler.frameGenImages() : null;
        }
        if (fgImages != null && frameGeneratedPresents++ == CAPTURE_AT) {
            java.nio.file.Path dir = IrisDlss.nativeDir.resolve("captures");
            try {
                java.nio.file.Files.createDirectories(dir);
                DlssNative.debugCapture(4, dir.toAbsolutePath().toString());
                IrisDlss.LOGGER.info("Iris DLSS: capturing frames into {}", dir);
            } catch (java.io.IOException e) {
                IrisDlss.LOGGER.warn("Iris DLSS: capture failed", e);
            }
        }
        long result = DlssNative.present(VulkanAccess.commandBuffer(), VulkanAccess.describe(view), fgImages,
            fgImages != null ? fg : null);
        if (result < 0) {
            disable(DlssNative.message());
            return false;
        }
        if ((result & DlssNative.PRESENT_FG_FAILED) != 0) {
            result &= ~DlssNative.PRESENT_FG_FAILED;
            IrisDlss.LOGGER.warn("Iris DLSS: frame generation off: {}", DlssNative.message());
        }
        // The presenter thread waits for this value: the game's frame submission signals it.
        VulkanAccess.encoder().signalSemaphore(timeline, result, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 10_000) {
            lastLogTime = now;
            IrisDlss.LOGGER.info("Iris DLSS: {} | {}", statusLine(), DlssUpscaler.statusLine());
        }
        return true;
    }

    /**
     * The game's swapchain image was acquired outside the game's surface code: something else (Lunar
     * Client's Multi-Threaded Rendering) acquires and presents, so the presenter can't take over.
     * Showing frames from both at once stalls in fullscreen, so the game keeps its output.
     */
    public static void foreignSwapchain() {
        // One frame can be acquired before DLSS was ready; only frames in a row count.
        if (!foreignSwapchain && ++foreignFrames >= 3) {
            foreignSwapchain = true;
            IrisDlss.LOGGER.warn("Iris DLSS: the game's swapchain is driven by another mod or client (Lunar Client: "
                + "Multi-Threaded Rendering), so frames stay on the game's swapchain and Frame Generation is off. "
                + "Turn that option off to use the DLSS presenter.");
        }
    }

    /** The game's own surface code acquired this frame again (the other mod's option was turned off). */
    public static void ownSwapchain() {
        foreignFrames = 0;
        if (foreignSwapchain) {
            foreignSwapchain = false;
            IrisDlss.LOGGER.info("Iris DLSS: the game's swapchain is free again, starting the presenter");
        }
    }

    private static boolean start(long sdlWindow, int w, int h) {
        stop();
        long hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow),
            SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        if (hwnd == 0L) {
            disable("no Win32 window handle");
            return false;
        }
        if (DlssNative.createPresenter(hwnd, w, h, vsync) != DlssNative.OK) {
            disable(DlssNative.message());
            return false;
        }
        timeline = DlssNative.presenterTimeline();
        width = w;
        height = h;
        running = true;
        DlssUpscaler.requestReset();
        IrisDlss.LOGGER.info("Iris DLSS: {}", DlssNative.message());
        return true;
    }

    /** Back to the game's own swapchain (DLSS off, setting off, or before recreating). */
    public static void stop() {
        if (!running) {
            return;
        }
        running = false;
        DlssNative.destroyPresenter();
        timeline = 0L;
    }

    private static void disable(String reason) {
        stop();
        failed = true;
        failureReason = reason;
        IrisDlss.LOGGER.error("Iris DLSS: presenter off, back to the game's swapchain: {}", reason);
    }

    /** Settings changed: try again (e.g. after a failure). */
    public static void retry() {
        failed = false;
        failureReason = "";
    }

    /** Frames per second the presenter showed, generated ones included (refreshed at most twice a second). */
    public static float shownFps() {
        refreshStats();
        return stats[0];
    }

    private static void refreshStats() {
        long now = System.currentTimeMillis();
        if (now - lastStatsTime > 500) {
            stats = DlssNative.presenterStats();
            lastStatsTime = now;
        }
    }

    /** F3 / settings line. */
    public static String statusLine() {
        if (!DlssConfig.vulkanPresent) {
            return "Output: game swapchain";
        }
        if (failed) {
            return "Output: game swapchain (presenter failed: " + failureReason + ")";
        }
        if (foreignSwapchain) {
            return "Output: game swapchain (Lunar Client's Multi-Threaded Rendering has the swapchain: turn it off "
                + "for Frame Generation)";
        }
        if (!DlssUpscaler.isActive()) {
            return "Output: game swapchain (DLSS off)";
        }
        if (!running) {
            return "Output: presenter (starting)";
        }
        refreshStats();
        String fg = !DlssConfig.frameGeneration ? "Frame Generation off"
            : stats[1] > 0 ? "Frame Generation x2" : "Frame Generation idle";
        return String.format("Output: presenter %s, %.0f fps shown (%.0f rendered), %s, pacing %.1f±%.1f ms "
            + "(worst %.1f), latency %.1f ms", vsync ? "VSync" : "no VSync", stats[0], stats[3], fg, stats[4], stats[5],
            stats[6], stats[7]);
    }
}
