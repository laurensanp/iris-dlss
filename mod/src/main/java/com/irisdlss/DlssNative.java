package com.irisdlss;

/** JNI bindings of irisdlss.dll (see dlss-bridge/src/jni.cpp and bridge.h). */
public final class DlssNative {

    public static final int OK = 0;
    public static final int DEVICE_FAILED = 3;
    public static final int FG_PARAM_COUNT = 86;
    /** Set in present()'s result when the real frame was queued but frame generation failed. */
    public static final long PRESENT_FG_FAILED = 1L << 62;

    private DlssNative() {
    }

    public static native void setDataPath(String dataPath);

    /** vkCreateInstance with NGX's extensions added; returns a VkResult. Pointers are native addresses. */
    public static native int createInstance(long getInstanceProcAddr, long createInfo, long allocator, long outInstance);

    /** vkCreateDevice with NGX's extensions, features and the presenter / optical-flow queues added. */
    public static native int createDevice(long getInstanceProcAddr, long instance, long physicalDevice, long createInfo,
        long allocator, long outDevice);

    /** NGX on the device created through createDevice. */
    public static native int init();

    public static native String message();

    /** After every submission of the game's command encoder. */
    public static native void frameSubmitted();

    /** {renderWidth, renderHeight, Float.floatToIntBits(sharpness)} or null. */
    public static native int[] optimalSettings(int outWidth, int outHeight, int quality);

    /** Creates the DLSS feature; its setup is recorded into commandBuffer. */
    public static native int createResources(long commandBuffer, int renderWidth, int renderHeight, int outWidth,
        int outHeight, int quality, boolean depthInverted, int preset);

    /** images: color, depth, motion, output; each {VkImage, VkImageView, VkFormat, width, height}. */
    public static native int evaluate(long commandBuffer, long[] images, float jitterX, float jitterY, float mvScaleX,
        float mvScaleY, boolean reset);

    public static native void destroyResources();

    public static native void shutdown();

    public static native int createPresenter(long hwnd, int width, int height, boolean vsync);

    public static native void destroyPresenter();

    public static native void setVsync(boolean vsync);

    /** VkSemaphore (timeline) to signal with present()'s value in the frame's submission. */
    public static native long presenterTimeline();

    /**
     * Records the frame copy (and frame generation, given depth / motion / hudless images and
     * FG_PARAM_COUNT floats, see bridge.h) into commandBuffer. Returns the timeline value to signal,
     * possibly with PRESENT_FG_FAILED set, or -status if nothing was queued.
     */
    public static native long present(long commandBuffer, long[] frame, long[] frameGenImages, float[] fgParams);

    /** Debug: PNGs of the generated and real frame of the next `frames` frame-generated presents. */
    public static native void debugCapture(int frames, String dir);

    /** {shown fps, frame generation ran (0/1), thread error (0/1), rendered fps, mean / stddev / worst ms between shown frames, latency ms}. */
    public static native float[] presenterStats();
}
