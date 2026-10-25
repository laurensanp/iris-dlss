// Iris DLSS bridge: runs NVIDIA DLSS (via NGX on Vulkan) on frames rendered by OpenGL.
#pragma once

#include <cstdint>

#ifdef IRISDLSS_BUILD
#define IRISDLSS_API extern "C" __declspec(dllexport)
#else
#define IRISDLSS_API extern "C" __declspec(dllimport)
#endif

enum IrisDlssStatus : int32_t {
    IRISDLSS_OK = 0,
    IRISDLSS_NO_VULKAN = 1,
    IRISDLSS_NO_MATCHING_GPU = 2,
    IRISDLSS_DEVICE_FAILED = 3,
    IRISDLSS_NGX_INIT_FAILED = 4,
    IRISDLSS_DLSS_UNAVAILABLE = 5,
    IRISDLSS_DRIVER_TOO_OLD = 6,
};

// Creates the Vulkan device and initializes NGX. deviceUuid (16 bytes) selects the GPU that also
// runs the OpenGL context; pass null to take the first NVIDIA GPU. dataPath receives NGX logs and
// must contain (or be next to) nvngx_dlss.dll.
IRISDLSS_API int32_t irisdlss_init(const uint8_t* deviceUuid, const wchar_t* dataPath);

IRISDLSS_API void irisdlss_shutdown();

// Human-readable description of the last error / the selected device.
IRISDLSS_API const char* irisdlss_last_message();

// Shared images (index order): 0 = color in (RGBA8, render size), 1 = depth in (R32F, render size),
// 2 = motion vectors (RG16F, render size, pixels, current -> previous), 3 = output (RGBA8, output size).
enum { IRISDLSS_IMAGE_COUNT = 4 };

struct IrisDlssSharedImage {
    uint64_t memoryHandle;  // Win32 NT handle for GL_EXT_memory_object_win32
    uint64_t memorySize;
    uint32_t width;
    uint32_t height;
};

struct IrisDlssResources {
    IrisDlssSharedImage images[IRISDLSS_IMAGE_COUNT];
    uint64_t glDoneSemaphore;  // signaled by OpenGL when the inputs are written
    uint64_t vkDoneSemaphore;  // signaled by Vulkan when the output is written
};

// (Re)creates the shared images, semaphores and the DLSS feature. Handles in `out` are owned by
// the caller once imported into OpenGL (the GL import takes its own reference; close them after).
IRISDLSS_API int32_t irisdlss_create_resources(uint32_t renderWidth, uint32_t renderHeight,
    uint32_t outWidth, uint32_t outHeight, int32_t quality, int32_t depthInverted, int32_t preset, IrisDlssResources* out);

IRISDLSS_API void irisdlss_destroy_resources();

// Queues one DLSS evaluation: waits for glDone, upscales, signals vkDone. jitter is in render pixels.
IRISDLSS_API int32_t irisdlss_evaluate(float jitterX, float jitterY, float mvScaleX, float mvScaleY,
    int32_t reset, float sharpness);

// Recommended render resolution for a DLSS quality mode (NVSDK_NGX_PerfQuality_Value) at the given
// output size. Returns 0 on success.
IRISDLSS_API int32_t irisdlss_optimal_settings(uint32_t outWidth, uint32_t outHeight, int32_t quality,
    uint32_t* renderWidth, uint32_t* renderHeight, float* sharpness);

// Diagnostic: is DLSS Frame Generation usable here? Tries to create the feature once.
IRISDLSS_API int32_t irisdlss_probe_frame_generation(uint32_t width, uint32_t height);

// ---- Presentation through Vulkan (optionally with DLSS Frame Generation) ----

struct IrisDlssPresentResources {
    IrisDlssSharedImage frame;   // RGBA8, window size: OpenGL copies the finished frame (GUI included) here
    uint64_t readySemaphore;     // signaled by OpenGL once the frame (and the DLSS images) are written
    uint64_t consumedSemaphore;  // signaled by Vulkan; OpenGL waits on it before writing them again
};

// Frame generation inputs, all float. Matrices are row-major (row-vector convention), without jitter,
// in the orientation of the images (row 0 = first row in memory).
enum {
    IRISDLSS_FG_VIEW_TO_CLIP = 0,
    IRISDLSS_FG_CLIP_TO_VIEW = 16,
    IRISDLSS_FG_CLIP_TO_PREV_CLIP = 32,
    IRISDLSS_FG_PREV_CLIP_TO_CLIP = 48,
    IRISDLSS_FG_JITTER = 64,        // x, y in render pixels
    IRISDLSS_FG_MVEC_SCALE = 66,    // x, y
    IRISDLSS_FG_CAMERA_POS = 68,    // xyz, then up, right, forward
    IRISDLSS_FG_CAMERA_UP = 71,
    IRISDLSS_FG_CAMERA_RIGHT = 74,
    IRISDLSS_FG_CAMERA_FWD = 77,
    IRISDLSS_FG_NEAR = 80,
    IRISDLSS_FG_FAR = 81,
    IRISDLSS_FG_FOV = 82,           // vertical, radians
    IRISDLSS_FG_ASPECT = 83,
    IRISDLSS_FG_RESET = 84,         // != 0: no connection to the previous frame
    IRISDLSS_FG_DEPTH_INVERTED = 85,
    IRISDLSS_FG_PARAM_COUNT = 86,
};

// Covers `parentHwnd`'s client area with a child window presented through a Vulkan swapchain.
// externalSync = 0 skips the OpenGL semaphores (standalone tests). Recreate on window resize.
IRISDLSS_API int32_t irisdlss_presenter_create(uint64_t parentHwnd, uint32_t width, uint32_t height,
    int32_t vsync, int32_t externalSync, IrisDlssPresentResources* out);

IRISDLSS_API void irisdlss_presenter_destroy();

IRISDLSS_API void irisdlss_presenter_set_vsync(int32_t vsync);

// Queues the frame written into the shared frame image. fgParams (IRISDLSS_FG_PARAM_COUNT floats)
// enables frame generation for this frame; null shows only the real frame. Needs the DLSS resources
// from irisdlss_create_resources for frame generation (depth, motion vectors, hudless = output).
IRISDLSS_API int32_t irisdlss_present(const float* fgParams);

// stats[0] = frames shown per second (generated ones included), stats[1] = 1 if frame generation ran
// in the last second, stats[2] = 1 if the presenter thread hit an error (see last_message).
IRISDLSS_API void irisdlss_presenter_stats(float stats[3]);
