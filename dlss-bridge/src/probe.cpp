// Check outside the game: runs the bridge the way Minecraft's Vulkan backend does. A small Vulkan
// "game" creates its instance and device through the bridge, records DLSS into its own command
// buffers and presents through the bridge's presenter (with frame generation) for a few seconds.
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_WIN32_KHR
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan.h>

#include <cstdio>
#include <string>
#include <vector>

#include "bridge.h"

namespace {

PFN_vkGetInstanceProcAddr gipa;
PFN_vkGetDeviceProcAddr gdpa;
VkInstance instance;
VkPhysicalDevice physical;
VkDevice device;
VkQueue queue;
uint32_t family;

#define DEVICE_FN(name) auto name = reinterpret_cast<PFN_##name>(gdpa(device, #name))

struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t width = 0;
    uint32_t height = 0;

    IrisDlssImage describe() const {
        return {reinterpret_cast<uint64_t>(image), reinterpret_cast<uint64_t>(view), format, width, height};
    }
};

bool createImage(Image& out, VkFormat format, uint32_t width, uint32_t height, VkImageUsageFlags usage) {
    DEVICE_FN(vkCreateImage);
    DEVICE_FN(vkGetImageMemoryRequirements);
    DEVICE_FN(vkAllocateMemory);
    DEVICE_FN(vkBindImageMemory);
    DEVICE_FN(vkCreateImageView);
    auto memoryProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(
        gipa(instance, "vkGetPhysicalDeviceMemoryProperties"));
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {width, height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.usage = usage;
    if (vkCreateImage(device, &info, nullptr, &out.image) != VK_SUCCESS) {
        return false;
    }
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(device, out.image, &requirements);
    VkPhysicalDeviceMemoryProperties props;
    memoryProperties(physical, &props);
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = requirements.size;
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
        if ((requirements.memoryTypeBits & (1u << i))
            && (props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            alloc.memoryTypeIndex = i;
            break;
        }
    }
    if (vkAllocateMemory(device, &alloc, nullptr, &out.memory) != VK_SUCCESS
        || vkBindImageMemory(device, out.image, out.memory, 0) != VK_SUCCESS) {
        return false;
    }
    bool depth = format == VK_FORMAT_D32_SFLOAT;
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = out.image;
    view.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view.format = format;
    view.subresourceRange = {depth ? VkImageAspectFlags(VK_IMAGE_ASPECT_DEPTH_BIT) : VkImageAspectFlags(VK_IMAGE_ASPECT_COLOR_BIT),
        0, 1, 0, 1};
    out.format = format;
    out.width = width;
    out.height = height;
    return vkCreateImageView(device, &view, nullptr, &out.view) == VK_SUCCESS;
}

void destroyImage(Image& image) {
    DEVICE_FN(vkDestroyImageView);
    DEVICE_FN(vkDestroyImage);
    DEVICE_FN(vkFreeMemory);
    vkDestroyImageView(device, image.view, nullptr);
    vkDestroyImage(device, image.image, nullptr);
    vkFreeMemory(device, image.memory, nullptr);
}

// Like RenderPearl: every image lives in GENERAL.
void toGeneral(VkCommandBuffer cmd, const Image& image) {
    DEVICE_FN(vkCmdPipelineBarrier);
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image.image;
    b.subresourceRange = {image.format == VK_FORMAT_D32_SFLOAT ? VkImageAspectFlags(VK_IMAGE_ASPECT_DEPTH_BIT)
        : VkImageAspectFlags(VK_IMAGE_ASPECT_COLOR_BIT), 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0,
        nullptr, 1, &b);
}

void clearColor(VkCommandBuffer cmd, const Image& image, float r, float g, float b) {
    DEVICE_FN(vkCmdClearColorImage);
    DEVICE_FN(vkCmdPipelineBarrier);
    VkClearColorValue value{{r, g, b, 1.0f}};
    VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(cmd, image.image, VK_IMAGE_LAYOUT_GENERAL, &value, 1, &range);
    VkMemoryBarrier m{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    m.srcAccessMask = m.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &m, 0,
        nullptr, 0, nullptr);
}

struct Frame {
    VkCommandPool pool;
    VkCommandBuffer cmd;
    VkFence fence;
};

VkCommandBuffer begin(Frame& frame) {
    DEVICE_FN(vkResetCommandBuffer);
    DEVICE_FN(vkBeginCommandBuffer);
    vkResetCommandBuffer(frame.cmd, 0);
    VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(frame.cmd, &info);
    return frame.cmd;
}

// Submits and waits; signals `timeline` to `value` like the game's frame submission does.
bool submit(Frame& frame, VkSemaphore timeline = VK_NULL_HANDLE, uint64_t value = 0) {
    DEVICE_FN(vkEndCommandBuffer);
    DEVICE_FN(vkQueueSubmit);
    DEVICE_FN(vkWaitForFences);
    DEVICE_FN(vkResetFences);
    vkEndCommandBuffer(frame.cmd);
    VkTimelineSemaphoreSubmitInfo timelineInfo{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
    timelineInfo.signalSemaphoreValueCount = 1;
    timelineInfo.pSignalSemaphoreValues = &value;
    VkSubmitInfo info{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    info.pNext = timeline ? &timelineInfo : nullptr;
    info.commandBufferCount = 1;
    info.pCommandBuffers = &frame.cmd;
    info.signalSemaphoreCount = timeline ? 1 : 0;
    info.pSignalSemaphores = &timeline;
    if (vkQueueSubmit(queue, 1, &info, frame.fence) != VK_SUCCESS) {
        return false;
    }
    vkWaitForFences(device, 1, &frame.fence, VK_TRUE, UINT64_MAX);
    vkResetFences(device, 1, &frame.fence);
    irisdlss_frame_submitted();
    return true;
}

bool createVulkan() {
    HMODULE loader = LoadLibraryW(L"vulkan-1.dll");
    if (!loader) {
        std::printf("vulkan-1.dll not found\n");
        return false;
    }
    gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(loader, "vkGetInstanceProcAddr"));
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Iris DLSS probe";
    app.apiVersion = VK_API_VERSION_1_2;
    const char* instanceExts[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WIN32_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    instanceInfo.pApplicationInfo = &app;
    instanceInfo.enabledExtensionCount = 2;
    instanceInfo.ppEnabledExtensionNames = instanceExts;
    VkResult result = static_cast<VkResult>(irisdlss_create_instance(reinterpret_cast<uint64_t>(gipa), &instanceInfo,
        nullptr, &instance));
    if (result != VK_SUCCESS) {
        std::printf("create_instance=%d\n", result);
        return false;
    }
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gipa(instance, "vkGetPhysicalDeviceProperties"));
    auto families = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
        gipa(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    uint32_t count = 0;
    enumerate(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    enumerate(instance, &count, devices.data());
    for (VkPhysicalDevice candidate : devices) {
        VkPhysicalDeviceProperties props;
        properties(candidate, &props);
        if (props.vendorID == 0x10DE) {
            physical = candidate;
            break;
        }
    }
    if (!physical) {
        std::printf("no NVIDIA GPU\n");
        return false;
    }
    families(physical, &count, nullptr);
    std::vector<VkQueueFamilyProperties> props(count);
    families(physical, &count, props.data());
    for (family = 0; family < count; family++) {
        if ((props[family].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT))
            == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            break;
        }
    }
    // What RenderPearl asks for that matters here: one graphics queue, swapchain, timeline semaphores.
    float priority = 0.0f;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = family;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* deviceExts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkPhysicalDeviceVulkan12Features features12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    features12.timelineSemaphore = VK_TRUE;
    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.pNext = &features12;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = deviceExts;
    result = static_cast<VkResult>(irisdlss_create_device(reinterpret_cast<uint64_t>(gipa),
        reinterpret_cast<uint64_t>(instance), reinterpret_cast<uint64_t>(physical), &deviceInfo, nullptr, &device));
    std::printf("create_device=%d %s\n", result, irisdlss_last_message());
    if (result != VK_SUCCESS) {
        return false;
    }
    gdpa = reinterpret_cast<PFN_vkGetDeviceProcAddr>(gipa(instance, "vkGetDeviceProcAddr"));
    DEVICE_FN(vkGetDeviceQueue);
    vkGetDeviceQueue(device, family, 0, &queue);
    return true;
}

bool createFrame(Frame& frame) {
    DEVICE_FN(vkCreateCommandPool);
    DEVICE_FN(vkAllocateCommandBuffers);
    DEVICE_FN(vkCreateFence);
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = family;
    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    return vkCreateCommandPool(device, &poolInfo, nullptr, &frame.pool) == VK_SUCCESS
        && (alloc.commandPool = frame.pool, vkAllocateCommandBuffers(device, &alloc, &frame.cmd) == VK_SUCCESS)
        && vkCreateFence(device, &fenceInfo, nullptr, &frame.fence) == VK_SUCCESS;
}

void fgParams(float* params, uint32_t renderWidth, uint32_t renderHeight, uint32_t w, uint32_t h, bool reset) {
    for (int i = 0; i < IRISDLSS_FG_PARAM_COUNT; i++) {
        params[i] = 0.0f;
    }
    for (int i = 0; i < 4; i++) {
        params[IRISDLSS_FG_VIEW_TO_CLIP + i * 5] = 1.0f;
        params[IRISDLSS_FG_CLIP_TO_VIEW + i * 5] = 1.0f;
        params[IRISDLSS_FG_CLIP_TO_PREV_CLIP + i * 5] = 1.0f;
        params[IRISDLSS_FG_PREV_CLIP_TO_CLIP + i * 5] = 1.0f;
    }
    params[IRISDLSS_FG_MVEC_SCALE] = 1.0f / renderWidth;
    params[IRISDLSS_FG_MVEC_SCALE + 1] = 1.0f / renderHeight;
    params[IRISDLSS_FG_CAMERA_UP + 1] = 1.0f;
    params[IRISDLSS_FG_CAMERA_RIGHT] = 1.0f;
    params[IRISDLSS_FG_CAMERA_FWD + 2] = -1.0f;
    params[IRISDLSS_FG_NEAR] = 0.05f;
    params[IRISDLSS_FG_FAR] = 1000.0f;
    params[IRISDLSS_FG_FOV] = 1.2f;
    params[IRISDLSS_FG_ASPECT] = static_cast<float>(w) / h;
    params[IRISDLSS_FG_RESET] = reset ? 1.0f : 0.0f;
    params[IRISDLSS_FG_DEPTH_INVERTED] = 1.0f;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    bool vsync = argc > 1 && std::wstring(argv[1]) == L"vsync";
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring dir(exePath);
    dir = dir.substr(0, dir.find_last_of(L"\\/"));
    irisdlss_set_data_path(dir.c_str());
    if (!createVulkan()) {
        return 1;
    }
    int32_t status = irisdlss_init();
    std::printf("init=%d %s\n", status, irisdlss_last_message());
    int failures = 0;
    if (status == IRISDLSS_OK) {
        const char* names[] = {"Performance", "Balanced", "Quality", "UltraPerformance", "UltraQuality", "DLAA"};
        for (int quality = 0; quality <= 5; quality++) {
            uint32_t w = 0, h = 0;
            float sharpness = 0;
            if (irisdlss_optimal_settings(1920, 1080, quality, &w, &h, &sharpness) == 0) {
                std::printf("  %-16s 1920x1080 <- %ux%u\n", names[quality], w, h);
            }
        }

        // A game window; DLSS output (= frame and hudless) at its client size.
        WNDCLASSW wc{};
        wc.lpfnWndProc = DefWindowProcW;
        wc.hInstance = GetModuleHandleW(nullptr);
        wc.lpszClassName = L"IrisDlssProbe";
        RegisterClassW(&wc);
        HWND window = CreateWindowW(L"IrisDlssProbe", L"Iris DLSS probe", WS_OVERLAPPEDWINDOW | WS_VISIBLE, 100, 100,
            1920 / 2, 1080 / 2, nullptr, nullptr, wc.hInstance, nullptr);
        RECT rect;
        GetClientRect(window, &rect);
        uint32_t w = rect.right - rect.left, h = rect.bottom - rect.top;
        uint32_t rw = w * 2 / 3, rh = h * 2 / 3;

        Frame frame{};
        Image color, depth, motion, output;
        const VkImageUsageFlags common = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        bool ok = createFrame(frame) && createImage(color, VK_FORMAT_R8G8B8A8_UNORM, rw, rh, common)
            && createImage(depth, VK_FORMAT_D32_SFLOAT, rw, rh, common | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
            && createImage(motion, VK_FORMAT_R16G16_SFLOAT, rw, rh, common | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
            && createImage(output, VK_FORMAT_R8G8B8A8_UNORM, w, h, common | VK_IMAGE_USAGE_STORAGE_BIT);
        std::printf("images=%d (%ux%u -> %ux%u)\n", ok, rw, rh, w, h);

        if (ok) {
            VkCommandBuffer cmd = begin(frame);
            for (const Image* image : {&color, &depth, &motion, &output}) {
                toGeneral(cmd, *image);
            }
            clearColor(cmd, motion, 0.0f, 0.0f, 0.0f);
            status = irisdlss_create_resources(reinterpret_cast<uint64_t>(cmd), rw, rh, w, h, 2, 1, 11);
            std::printf("create_resources=%d %s\n", status, irisdlss_last_message());
            submit(frame);
        }
        if (ok && status == IRISDLSS_OK) {
            status = irisdlss_presenter_create(reinterpret_cast<uint64_t>(window), w, h, vsync ? 1 : 0);
            std::printf("presenter_create=%d %s\n", status, irisdlss_last_message());
            VkSemaphore timeline = reinterpret_cast<VkSemaphore>(irisdlss_presenter_timeline());
            float params[IRISDLSS_FG_PARAM_COUNT];
            for (int i = 0; i < 240 && status == IRISDLSS_OK; i++) {
                MSG msg;
                while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
                    DispatchMessageW(&msg);
                }
                irisdlss_presenter_frame_start();
                VkCommandBuffer cmd = begin(frame);
                float t = i / 240.0f;
                clearColor(cmd, color, t, 0.3f, 1.0f - t);
                IrisDlssImage c = color.describe(), d = depth.describe(), m = motion.describe(), o = output.describe();
                int32_t evaluated = irisdlss_evaluate(reinterpret_cast<uint64_t>(cmd), &c, &d, &m, &o, 0.0f, 0.0f,
                    1.0f, 1.0f, i == 0);
                if (evaluated != IRISDLSS_OK && failures++ < 3) {
                    std::printf("  evaluate frame %d: %s\n", i, irisdlss_last_message());
                }
                fgParams(params, rw, rh, w, h, i == 0);
                uint64_t value = 0;
                int32_t presented = irisdlss_present(reinterpret_cast<uint64_t>(cmd), &o, &d, &m, &o, params, &value);
                if (presented != IRISDLSS_OK && failures++ < 3) {
                    std::printf("  present frame %d: %s\n", i, irisdlss_last_message());
                }
                if (!submit(frame, value ? timeline : VK_NULL_HANDLE, value)) {
                    std::printf("  submit failed at frame %d\n", i);
                    break;
                }
                if (!vsync) {
                    Sleep(8);
                }
            }
            float stats[IRISDLSS_STATS_COUNT];
            irisdlss_presenter_stats(stats);
            std::printf("presented fps=%.1f (rendered %.1f) frameGeneration=%d threadError=%d %s\n", stats[0],
                stats[3], int(stats[1]), int(stats[2]), irisdlss_last_message());
            std::printf("pacing: %.2f ms between frames, jitter %.2f ms, worst %.2f ms, latency %.2f ms\n", stats[4],
                stats[5], stats[6], stats[7]);
            irisdlss_presenter_destroy();
        }
        DEVICE_FN(vkDeviceWaitIdle);
        vkDeviceWaitIdle(device);
        irisdlss_shutdown();
        for (Image* image : {&color, &depth, &motion, &output}) {
            if (image->image) destroyImage(*image);
        }
        DestroyWindow(window);
    }
    auto destroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(gdpa(device, "vkDestroyDevice"));
    auto destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(gipa(instance, "vkDestroyInstance"));
    destroyDevice(device, nullptr);
    destroyInstance(instance, nullptr);
    std::printf("%s\n", status == IRISDLSS_OK && failures == 0 ? "PROBE OK" : "PROBE FAILED");
    return status == IRISDLSS_OK && failures == 0 ? 0 : 1;
}
