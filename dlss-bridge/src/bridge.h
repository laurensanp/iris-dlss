// Iris DLSS bridge: runs NVIDIA DLSS (via NGX) inside Minecraft's own Vulkan device.
//
// Nothing here creates a Vulkan device of its own. The game's Vulkan backend (RenderPearl) creates
// its instance and device through irisdlss_create_instance / irisdlss_create_device, which add the
// extensions, features and queues NGX needs. Afterwards DLSS work is recorded straight into the
// game's command buffers on images the game owns, so no memory or semaphore sharing is involved.
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

// Human-readable description of the last error / the selected device.
IRISDLSS_API const char* irisdlss_last_message();

// Folder with nvngx_dlss.dll / nvngx_dlssg.dll; NGX also writes its logs there. Call first.
IRISDLSS_API void irisdlss_set_data_path(const wchar_t* dataPath);

// ---- Device creation (called in place of vkCreateInstance / vkCreateDevice) --------------------
// Same contract as the Vulkan functions (VkResult). They create exactly what was asked for plus
// NGX's needs; if that fails they retry with the unmodified create info. gipa is the
// vkGetInstanceProcAddr of the loader the caller uses.

IRISDLSS_API int32_t irisdlss_create_instance(uint64_t gipa, const void* createInfo, const void* allocator,
    void* outInstance);

// Also asks for one more queue in the graphics family (the presenter thread's) and an optical-flow
// queue for DLSS Frame Generation, and remembers instance / physical device / device for NGX.
IRISDLSS_API int32_t irisdlss_create_device(uint64_t gipa, uint64_t instance, uint64_t physicalDevice,
    const void* createInfo, const void* allocator, void* outDevice);

// Initializes NGX on the device made by irisdlss_create_device.
IRISDLSS_API int32_t irisdlss_init();

// Before the game destroys its device (after it waited for the GPU): releases everything.
IRISDLSS_API void irisdlss_shutdown();

// Once per vkQueueSubmit of the game's frame: retires NGX features no frame in flight still uses.
IRISDLSS_API void irisdlss_frame_submitted();

// An image owned by the game, in VK_IMAGE_LAYOUT_GENERAL (RenderPearl keeps all images there).
struct IrisDlssImage {
    uint64_t image;   // VkImage
    uint64_t view;    // VkImageView (depth aspect for depth formats)
    int32_t format;   // VkFormat
    uint32_t width;
    uint32_t height;
};

// ---- DLSS Super Resolution -------------------------------------------------------------------

// Recommended render resolution for a DLSS quality mode (NVSDK_NGX_PerfQuality_Value) at the given
// output size. Returns 0 on success.
IRISDLSS_API int32_t irisdlss_optimal_settings(uint32_t outWidth, uint32_t outHeight, int32_t quality,
    uint32_t* renderWidth, uint32_t* renderHeight, float* sharpness);

// (Re)creates the DLSS feature; its setup work is recorded into commandBuffer.
IRISDLSS_API int32_t irisdlss_create_resources(uint64_t commandBuffer, uint32_t renderWidth, uint32_t renderHeight,
    uint32_t outWidth, uint32_t outHeight, int32_t quality, int32_t depthInverted, int32_t preset);

IRISDLSS_API void irisdlss_destroy_resources();

// Records one DLSS evaluation into commandBuffer (outside a render pass). Motion vectors are in
// render pixels (current -> previous); output needs VK_IMAGE_USAGE_STORAGE_BIT. Images are back in
// GENERAL afterwards. jitter is in render pixels.
IRISDLSS_API int32_t irisdlss_evaluate(uint64_t commandBuffer, const IrisDlssImage* color, const IrisDlssImage* depth,
    const IrisDlssImage* motion, const IrisDlssImage* output, float jitterX, float jitterY, float mvScaleX,
    float mvScaleY, int32_t reset);

// ---- Presentation (optionally with DLSS Frame Generation) --------------------------------------

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

// Covers parentHwnd's client area with a child window presented through a Vulkan swapchain on the
// presenter queue. Recreate on window resize.
IRISDLSS_API int32_t irisdlss_presenter_create(uint64_t parentHwnd, uint32_t width, uint32_t height, int32_t vsync);

IRISDLSS_API void irisdlss_presenter_destroy();

IRISDLSS_API void irisdlss_presenter_set_vsync(int32_t vsync);

// Timeline semaphore the game's submission signals once a presented frame is rendered.
IRISDLSS_API uint64_t irisdlss_presenter_timeline();

// Records the copy of the finished frame (and, with fgParams and the three DLSS images, the DLSS-G
// generated frame) into commandBuffer and queues it for the presenter thread. The caller signals
// irisdlss_presenter_timeline() with *signalValue in the same submission. Images are image-sized
// like the presenter (frame, hudless) or render-sized (depth, motion).
IRISDLSS_API int32_t irisdlss_present(uint64_t commandBuffer, const IrisDlssImage* frame, const IrisDlssImage* depth,
    const IrisDlssImage* motion, const IrisDlssImage* hudless, const float* fgParams, uint64_t* signalValue);

// Debug: writes the generated and real frame of the next `frames` frame-generated presents as PNG files
// into dir (fg_NNN_a_generated.png, fg_NNN_b_real.png).
IRISDLSS_API void irisdlss_debug_capture(int32_t frames, const wchar_t* dir);

// stats[0] = frames shown per second (generated ones included), [1] = 1 if frame generation ran in
// the last second, [2] = 1 if the presenter thread hit an error (see last_message), [3] = frames the
// game renders per second, [4] = mean time between shown frames (ms), [5] = its standard deviation
// (ms), [6] = the longest gap (ms), [7] = latency from irisdlss_present to the real frame's present (ms).
enum { IRISDLSS_STATS_COUNT = 8 };
IRISDLSS_API void irisdlss_presenter_stats(float stats[IRISDLSS_STATS_COUNT]);
