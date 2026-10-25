// JNI entry points for com.irisdlss.DlssNative.
#define IRISDLSS_BUILD
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>

#include <string>

#include "bridge.h"

namespace {

// Images come from Java as long[5 * n]: {VkImage, VkImageView, VkFormat, width, height} each.
constexpr int kImageLongs = 5;

bool readImages(JNIEnv* env, jlongArray array, IrisDlssImage* out, int count) {
    if (array == nullptr || env->GetArrayLength(array) < count * kImageLongs) {
        return false;
    }
    jlong values[kImageLongs * 4];
    env->GetLongArrayRegion(array, 0, count * kImageLongs, values);
    for (int i = 0; i < count; i++) {
        const jlong* v = values + i * kImageLongs;
        out[i] = {static_cast<uint64_t>(v[0]), static_cast<uint64_t>(v[1]), static_cast<int32_t>(v[2]),
            static_cast<uint32_t>(v[3]), static_cast<uint32_t>(v[4])};
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_setDataPath(JNIEnv* env, jclass, jstring dataPath) {
    const jchar* path = env->GetStringChars(dataPath, nullptr);
    std::wstring widePath(reinterpret_cast<const wchar_t*>(path), env->GetStringLength(dataPath));
    env->ReleaseStringChars(dataPath, path);
    irisdlss_set_data_path(widePath.c_str());
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_createInstance(JNIEnv*, jclass, jlong gipa, jlong createInfo,
    jlong allocator, jlong outInstance) {
    return irisdlss_create_instance(static_cast<uint64_t>(gipa), reinterpret_cast<const void*>(createInfo),
        reinterpret_cast<const void*>(allocator), reinterpret_cast<void*>(outInstance));
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_createDevice(JNIEnv*, jclass, jlong gipa, jlong instance,
    jlong physicalDevice, jlong createInfo, jlong allocator, jlong outDevice) {
    return irisdlss_create_device(static_cast<uint64_t>(gipa), static_cast<uint64_t>(instance),
        static_cast<uint64_t>(physicalDevice), reinterpret_cast<const void*>(createInfo),
        reinterpret_cast<const void*>(allocator), reinterpret_cast<void*>(outDevice));
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_init(JNIEnv*, jclass) {
    return irisdlss_init();
}

JNIEXPORT jstring JNICALL Java_com_irisdlss_DlssNative_message(JNIEnv* env, jclass) {
    return env->NewStringUTF(irisdlss_last_message());
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_frameSubmitted(JNIEnv*, jclass) {
    irisdlss_frame_submitted();
}

JNIEXPORT jintArray JNICALL Java_com_irisdlss_DlssNative_optimalSettings(JNIEnv* env, jclass, jint width,
    jint height, jint quality) {
    uint32_t renderWidth = 0, renderHeight = 0;
    float sharpness = 0.0f;
    if (irisdlss_optimal_settings(width, height, quality, &renderWidth, &renderHeight, &sharpness) != 0) {
        return nullptr;
    }
    jint values[3] = {static_cast<jint>(renderWidth), static_cast<jint>(renderHeight),
        *reinterpret_cast<jint*>(&sharpness)};
    jintArray result = env->NewIntArray(3);
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_createResources(JNIEnv*, jclass, jlong commandBuffer,
    jint renderWidth, jint renderHeight, jint outWidth, jint outHeight, jint quality, jboolean depthInverted,
    jint preset) {
    return irisdlss_create_resources(static_cast<uint64_t>(commandBuffer), renderWidth, renderHeight, outWidth,
        outHeight, quality, depthInverted ? 1 : 0, preset);
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_evaluate(JNIEnv* env, jclass, jlong commandBuffer,
    jlongArray images, jfloat jitterX, jfloat jitterY, jfloat mvScaleX, jfloat mvScaleY, jboolean reset) {
    IrisDlssImage list[4];
    if (!readImages(env, images, list, 4)) {
        return IRISDLSS_DLSS_UNAVAILABLE;
    }
    return irisdlss_evaluate(static_cast<uint64_t>(commandBuffer), &list[0], &list[1], &list[2], &list[3], jitterX,
        jitterY, mvScaleX, mvScaleY, reset ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_destroyResources(JNIEnv*, jclass) {
    irisdlss_destroy_resources();
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_shutdown(JNIEnv*, jclass) {
    irisdlss_shutdown();
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_createPresenter(JNIEnv*, jclass, jlong hwnd, jint width,
    jint height, jboolean vsync) {
    return irisdlss_presenter_create(static_cast<uint64_t>(hwnd), width, height, vsync ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_destroyPresenter(JNIEnv*, jclass) {
    irisdlss_presenter_destroy();
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_setVsync(JNIEnv*, jclass, jboolean vsync) {
    irisdlss_presenter_set_vsync(vsync ? 1 : 0);
}

JNIEXPORT jlong JNICALL Java_com_irisdlss_DlssNative_presenterTimeline(JNIEnv*, jclass) {
    return static_cast<jlong>(irisdlss_presenter_timeline());
}

// Returns the timeline value to signal (> 0), or -status if nothing was queued.
JNIEXPORT jlong JNICALL Java_com_irisdlss_DlssNative_present(JNIEnv* env, jclass, jlong commandBuffer,
    jlongArray frame, jlongArray frameGenImages, jfloatArray fgParams) {
    IrisDlssImage frameImage;
    if (!readImages(env, frame, &frameImage, 1)) {
        return -IRISDLSS_DEVICE_FAILED;
    }
    IrisDlssImage fgImages[3];
    bool hasFgImages = readImages(env, frameGenImages, fgImages, 3);
    float params[IRISDLSS_FG_PARAM_COUNT];
    bool hasParams = fgParams != nullptr && env->GetArrayLength(fgParams) >= IRISDLSS_FG_PARAM_COUNT;
    if (hasParams) {
        env->GetFloatArrayRegion(fgParams, 0, IRISDLSS_FG_PARAM_COUNT, params);
    }
    uint64_t value = 0;
    int32_t status = irisdlss_present(static_cast<uint64_t>(commandBuffer), &frameImage,
        hasFgImages ? &fgImages[0] : nullptr, hasFgImages ? &fgImages[1] : nullptr,
        hasFgImages ? &fgImages[2] : nullptr, hasFgImages && hasParams ? params : nullptr, &value);
    if (value == 0) {
        return -(status == IRISDLSS_OK ? IRISDLSS_DEVICE_FAILED : status);
    }
    // A failed frame generation still queued the real frame; the caller reads message() for why.
    return status == IRISDLSS_OK ? static_cast<jlong>(value) : static_cast<jlong>(value) | (1ll << 62);
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_debugCapture(JNIEnv* env, jclass, jint frames, jstring dir) {
    const jchar* chars = env->GetStringChars(dir, nullptr);
    std::wstring path(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(dir));
    env->ReleaseStringChars(dir, chars);
    irisdlss_debug_capture(frames, path.c_str());
}

JNIEXPORT jfloatArray JNICALL Java_com_irisdlss_DlssNative_presenterStats(JNIEnv* env, jclass) {
    float stats[IRISDLSS_STATS_COUNT] = {};
    irisdlss_presenter_stats(stats);
    jfloatArray result = env->NewFloatArray(IRISDLSS_STATS_COUNT);
    env->SetFloatArrayRegion(result, 0, IRISDLSS_STATS_COUNT, stats);
    return result;
}

}  // extern "C"
