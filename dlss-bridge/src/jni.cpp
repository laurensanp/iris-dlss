// JNI entry points for com.irisdlss.DlssNative.
#define IRISDLSS_BUILD
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>

#include <string>

#include "bridge.h"

extern "C" {

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_init(JNIEnv* env, jclass, jbyteArray uuid, jstring dataPath) {
    jbyte uuidBytes[16] = {};
    bool hasUuid = uuid != nullptr && env->GetArrayLength(uuid) >= 16;
    if (hasUuid) {
        env->GetByteArrayRegion(uuid, 0, 16, uuidBytes);
    }
    const jchar* path = env->GetStringChars(dataPath, nullptr);
    std::wstring widePath(reinterpret_cast<const wchar_t*>(path), env->GetStringLength(dataPath));
    env->ReleaseStringChars(dataPath, path);
    return irisdlss_init(hasUuid ? reinterpret_cast<const uint8_t*>(uuidBytes) : nullptr, widePath.c_str());
}

JNIEXPORT jstring JNICALL Java_com_irisdlss_DlssNative_message(JNIEnv* env, jclass) {
    return env->NewStringUTF(irisdlss_last_message());
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

// Layout: per image {handle, size, width, height}, then glDone and vkDone semaphore handles.
JNIEXPORT jlongArray JNICALL Java_com_irisdlss_DlssNative_createResources(JNIEnv* env, jclass, jint renderWidth,
    jint renderHeight, jint outWidth, jint outHeight, jint quality, jboolean depthInverted, jint preset) {
    IrisDlssResources resources{};
    if (irisdlss_create_resources(renderWidth, renderHeight, outWidth, outHeight, quality,
            depthInverted ? 1 : 0, preset, &resources) != IRISDLSS_OK) {
        return nullptr;
    }
    jlong values[IRISDLSS_IMAGE_COUNT * 4 + 2];
    for (int i = 0; i < IRISDLSS_IMAGE_COUNT; i++) {
        values[i * 4] = static_cast<jlong>(resources.images[i].memoryHandle);
        values[i * 4 + 1] = static_cast<jlong>(resources.images[i].memorySize);
        values[i * 4 + 2] = resources.images[i].width;
        values[i * 4 + 3] = resources.images[i].height;
    }
    values[IRISDLSS_IMAGE_COUNT * 4] = static_cast<jlong>(resources.glDoneSemaphore);
    values[IRISDLSS_IMAGE_COUNT * 4 + 1] = static_cast<jlong>(resources.vkDoneSemaphore);
    jlongArray result = env->NewLongArray(IRISDLSS_IMAGE_COUNT * 4 + 2);
    env->SetLongArrayRegion(result, 0, IRISDLSS_IMAGE_COUNT * 4 + 2, values);
    return result;
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_evaluate(JNIEnv*, jclass, jfloat jitterX, jfloat jitterY,
    jfloat mvScaleX, jfloat mvScaleY, jboolean reset, jfloat sharpness) {
    return irisdlss_evaluate(jitterX, jitterY, mvScaleX, mvScaleY, reset ? 1 : 0, sharpness);
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_destroyResources(JNIEnv*, jclass) {
    irisdlss_destroy_resources();
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_shutdown(JNIEnv*, jclass) {
    irisdlss_shutdown();
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_closeHandle(JNIEnv*, jclass, jlong handle) {
    if (handle != 0) {
        CloseHandle(reinterpret_cast<HANDLE>(handle));
    }
}

// Layout: frame image {handle, size, width, height}, ready semaphore, consumed semaphore.
JNIEXPORT jlongArray JNICALL Java_com_irisdlss_DlssNative_createPresenter(JNIEnv* env, jclass, jlong hwnd,
    jint width, jint height, jboolean vsync) {
    IrisDlssPresentResources resources{};
    if (irisdlss_presenter_create(static_cast<uint64_t>(hwnd), width, height, vsync ? 1 : 0, 1, &resources)
        != IRISDLSS_OK) {
        return nullptr;
    }
    jlong values[6] = {static_cast<jlong>(resources.frame.memoryHandle), static_cast<jlong>(resources.frame.memorySize),
        resources.frame.width, resources.frame.height, static_cast<jlong>(resources.readySemaphore),
        static_cast<jlong>(resources.consumedSemaphore)};
    jlongArray result = env->NewLongArray(6);
    env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_destroyPresenter(JNIEnv*, jclass) {
    irisdlss_presenter_destroy();
}

JNIEXPORT void JNICALL Java_com_irisdlss_DlssNative_setVsync(JNIEnv*, jclass, jboolean vsync) {
    irisdlss_presenter_set_vsync(vsync ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_com_irisdlss_DlssNative_present(JNIEnv* env, jclass, jfloatArray fgParams) {
    if (fgParams == nullptr || env->GetArrayLength(fgParams) < IRISDLSS_FG_PARAM_COUNT) {
        return irisdlss_present(nullptr);
    }
    float params[IRISDLSS_FG_PARAM_COUNT];
    env->GetFloatArrayRegion(fgParams, 0, IRISDLSS_FG_PARAM_COUNT, params);
    return irisdlss_present(params);
}

JNIEXPORT jfloatArray JNICALL Java_com_irisdlss_DlssNative_presenterStats(JNIEnv* env, jclass) {
    float stats[3] = {};
    irisdlss_presenter_stats(stats);
    jfloatArray result = env->NewFloatArray(3);
    env->SetFloatArrayRegion(result, 0, 3, stats);
    return result;
}

}  // extern "C"
