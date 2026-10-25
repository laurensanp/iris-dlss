package com.irisdlss;

/** JNI bindings of irisdlss.dll (see dlss-bridge/src/jni.cpp). */
public final class DlssNative {

    public static final int OK = 0;

    private DlssNative() {
    }

    public static native int init(byte[] deviceUuid, String dataPath);

    public static native String message();

    /** {renderWidth, renderHeight, Float.floatToIntBits(sharpness)} or null. */
    public static native int[] optimalSettings(int outWidth, int outHeight, int quality);

    /** Per image {memoryHandle, size, width, height} x4, then glDone and vkDone semaphores; or null. */
    public static native long[] createResources(int renderWidth, int renderHeight, int outWidth, int outHeight,
        int quality, boolean depthInverted, int preset);

    public static native int evaluate(float jitterX, float jitterY, float mvScaleX, float mvScaleY,
        boolean reset, float sharpness);

    public static native void destroyResources();

    public static native void shutdown();

    public static native void closeHandle(long handle);

    public static final int DEVICE_FAILED = 3;
    public static final int FG_PARAM_COUNT = 86;

    /** Frame image {memoryHandle, size, width, height}, then ready and consumed semaphores; or null. */
    public static native long[] createPresenter(long hwnd, int width, int height, boolean vsync);

    public static native void destroyPresenter();

    public static native void setVsync(boolean vsync);

    /** Shows the frame image; fgParams (FG_PARAM_COUNT floats, see bridge.h) enables frame generation. */
    public static native int present(float[] fgParams);

    /** {frames shown per second, frame generation ran (0/1), presenter thread error (0/1)}. */
    public static native float[] presenterStats();
}
