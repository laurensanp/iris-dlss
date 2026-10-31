// SIRIS bridge: runs NVIDIA DLSS (via NGX) inside Minecraft's own Vulkan device.
//
// Nothing here creates a Vulkan device of its own. The game's Vulkan backend (RenderPearl) creates
// its instance and device through siris_create_instance / siris_create_device, which add the
// extensions, features and queues NGX needs. Afterwards DLSS work is recorded straight into the
// game's command buffers on images the game owns, so no memory or semaphore sharing is involved.
#pragma once

#include <cstdint>

#ifdef SIRIS_BUILD
#define SIRIS_API extern "C" __declspec(dllexport)
#else
#define SIRIS_API extern "C" __declspec(dllimport)
#endif

enum SirisStatus : int32_t {
    SIRIS_OK = 0,
    SIRIS_NO_VULKAN = 1,
    SIRIS_NO_MATCHING_GPU = 2,
    SIRIS_DEVICE_FAILED = 3,
    SIRIS_NGX_INIT_FAILED = 4,
    SIRIS_DLSS_UNAVAILABLE = 5,
    SIRIS_DRIVER_TOO_OLD = 6,
};

// Human-readable description of the last error / the selected device.
SIRIS_API const char* siris_last_message();

// Folder with nvngx_dlss.dll / nvngx_dlssg.dll; NGX also writes its logs there. Call first.
SIRIS_API void siris_set_data_path(const wchar_t* dataPath);

// ---- Device creation (called in place of vkCreateInstance / vkCreateDevice) --------------------
// Same contract as the Vulkan functions (VkResult). They create exactly what was asked for plus
// NGX's needs; if that fails they retry with the unmodified create info. gipa is the
// vkGetInstanceProcAddr of the loader the caller uses.

SIRIS_API int32_t siris_create_instance(uint64_t gipa, const void* createInfo, const void* allocator,
    void* outInstance);

// Also asks for one more queue in the graphics family (the presenter thread's) and an optical-flow
// queue for DLSS Frame Generation, and remembers instance / physical device / device for NGX.
SIRIS_API int32_t siris_create_device(uint64_t gipa, uint64_t instance, uint64_t physicalDevice,
    const void* createInfo, const void* allocator, void* outDevice);

// Initializes NGX on the device made by siris_create_device.
SIRIS_API int32_t siris_init();

// Before the game destroys its device (after it waited for the GPU): releases everything.
SIRIS_API void siris_shutdown();

// Once per vkQueueSubmit of the game's frame: retires NGX features no frame in flight still uses.
SIRIS_API void siris_frame_submitted();

// An image owned by the game, in VK_IMAGE_LAYOUT_GENERAL (RenderPearl keeps all images there).
struct SirisImage {
    uint64_t image;   // VkImage
    uint64_t view;    // VkImageView (depth aspect for depth formats)
    int32_t format;   // VkFormat
    uint32_t width;
    uint32_t height;
};

// ---- DLSS Super Resolution -------------------------------------------------------------------

// Recommended render resolution for a DLSS quality mode (NVSDK_NGX_PerfQuality_Value) at the given
// output size. Returns 0 on success.
SIRIS_API int32_t siris_optimal_settings(uint32_t outWidth, uint32_t outHeight, int32_t quality,
    uint32_t* renderWidth, uint32_t* renderHeight, float* sharpness);

// (Re)creates the DLSS feature; its setup work is recorded into commandBuffer.
SIRIS_API int32_t siris_create_resources(uint64_t commandBuffer, uint32_t renderWidth, uint32_t renderHeight,
    uint32_t outWidth, uint32_t outHeight, int32_t quality, int32_t depthInverted, int32_t preset);

SIRIS_API void siris_destroy_resources();

// Records one DLSS evaluation into commandBuffer (outside a render pass). Motion vectors are in
// render pixels (current -> previous); output needs VK_IMAGE_USAGE_STORAGE_BIT. Images are back in
// GENERAL afterwards. jitter is in render pixels.
SIRIS_API int32_t siris_evaluate(uint64_t commandBuffer, const SirisImage* color, const SirisImage* depth,
    const SirisImage* motion, const SirisImage* output, float jitterX, float jitterY, float mvScaleX,
    float mvScaleY, int32_t reset);

// ---- Presentation (optionally with DLSS Frame Generation) --------------------------------------

// Frame generation inputs, all float. Matrices are row-major (row-vector convention), without jitter,
// in the orientation of the images (row 0 = first row in memory).
enum {
    SIRIS_FG_VIEW_TO_CLIP = 0,
    SIRIS_FG_CLIP_TO_VIEW = 16,
    SIRIS_FG_CLIP_TO_PREV_CLIP = 32,
    SIRIS_FG_PREV_CLIP_TO_CLIP = 48,
    SIRIS_FG_JITTER = 64,        // x, y in render pixels
    SIRIS_FG_MVEC_SCALE = 66,    // x, y
    SIRIS_FG_CAMERA_POS = 68,    // xyz, then up, right, forward
    SIRIS_FG_CAMERA_UP = 71,
    SIRIS_FG_CAMERA_RIGHT = 74,
    SIRIS_FG_CAMERA_FWD = 77,
    SIRIS_FG_NEAR = 80,
    SIRIS_FG_FAR = 81,
    SIRIS_FG_FOV = 82,           // vertical, radians
    SIRIS_FG_ASPECT = 83,
    SIRIS_FG_RESET = 84,         // != 0: no connection to the previous frame
    SIRIS_FG_DEPTH_INVERTED = 85,
    SIRIS_FG_PARAM_COUNT = 86,
};

// Covers parentHwnd's client area with a child window presented through a Vulkan swapchain on the
// presenter queue. Recreate on window resize.
SIRIS_API int32_t siris_presenter_create(uint64_t parentHwnd, uint32_t width, uint32_t height, int32_t vsync);

SIRIS_API void siris_presenter_destroy();

SIRIS_API void siris_presenter_set_vsync(int32_t vsync);

// Highest frame generation multiplier the device supports after siris_init: 0 = no frame generation,
// 2 = one generated frame per rendered one, 3 or 4 = DLSS 4 multi frame generation (RTX 50).
SIRIS_API int32_t siris_frame_generation_max();

// Generated frames per rendered frame from the next present on (1 = 2x ... 3 = 4x), clamped to what the
// device supports.
SIRIS_API void siris_presenter_set_generated(int32_t count);

// Start of the game's frame, before it reads input: waits until the presenter took the previous
// frame and, with VSync, until this frame can be finished just in time (keeps latency low).
SIRIS_API void siris_presenter_frame_start();

// Timeline semaphore the game's submission signals once a presented frame is rendered.
SIRIS_API uint64_t siris_presenter_timeline();

// Records the copy of the finished frame (and, with fgParams and the three DLSS images, the DLSS-G
// generated frame) into commandBuffer and queues it for the presenter thread. The caller signals
// siris_presenter_timeline() with *signalValue in the same submission. Images are image-sized
// like the presenter (frame, hudless) or render-sized (depth, motion).
SIRIS_API int32_t siris_present(uint64_t commandBuffer, const SirisImage* frame, const SirisImage* depth,
    const SirisImage* motion, const SirisImage* hudless, const float* fgParams, uint64_t* signalValue);

// Debug: writes the generated and real frame of the next `frames` frame-generated presents as PNG files
// into dir (fg_NNN_a_generated.png, fg_NNN_b_real.png).
SIRIS_API void siris_debug_capture(int32_t frames, const wchar_t* dir);

// stats[0] = frames shown per second (generated ones included), [1] = the frame generation multiplier
// (2..4) if it ran in the last second, else 0, [2] = 1 if the presenter thread hit an error (see last_message), [3] = frames the
// game renders per second, [4] = mean time between shown frames (ms), [5] = its standard deviation
// (ms), [6] = the longest gap (ms), [7] = latency from siris_present to the real frame's present (ms),
// [8] = 1% low of shown frames over the last 5 seconds (fps).
enum { SIRIS_STATS_COUNT = 9 };
SIRIS_API void siris_presenter_stats(float stats[SIRIS_STATS_COUNT]);
