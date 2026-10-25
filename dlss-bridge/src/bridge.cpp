#define IRISDLSS_BUILD
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_WIN32_KHR
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan.h>

#include <nvsdk_ngx_vk.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_helpers_dlssg_vk.h>

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "bridge.h"

namespace {

// Identifies this integration to NGX (custom engine, no NVIDIA application id).
constexpr const char* kProjectId = "6f1c3a52-6a8b-4d1e-9a0d-2f7b5c9e1a44";
constexpr const char* kEngineVersion = "26.3";

std::string g_message;

struct Vk {
    HMODULE loader = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    PFN_vkCreateInstance createInstance = nullptr;
    PFN_vkDestroyInstance destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 getPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getQueueFamilyProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensions = nullptr;
    PFN_vkCreateDevice createDevice = nullptr;
    PFN_vkDestroyDevice destroyDevice = nullptr;
    PFN_vkGetDeviceQueue getDeviceQueue = nullptr;
    PFN_vkDeviceWaitIdle deviceWaitIdle = nullptr;

    PFN_vkGetPhysicalDeviceMemoryProperties getMemoryProperties = nullptr;

    // Device-level functions.
    PFN_vkCreateImage createImage = nullptr;
    PFN_vkDestroyImage destroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements getImageMemoryRequirements = nullptr;
    PFN_vkAllocateMemory allocateMemory = nullptr;
    PFN_vkFreeMemory freeMemory = nullptr;
    PFN_vkBindImageMemory bindImageMemory = nullptr;
    PFN_vkCreateImageView createImageView = nullptr;
    PFN_vkDestroyImageView destroyImageView = nullptr;
    PFN_vkGetMemoryWin32HandleKHR getMemoryWin32Handle = nullptr;
    PFN_vkCreateSemaphore createSemaphore = nullptr;
    PFN_vkDestroySemaphore destroySemaphore = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR getSemaphoreWin32Handle = nullptr;
    PFN_vkCreateCommandPool createCommandPool = nullptr;
    PFN_vkDestroyCommandPool destroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers allocateCommandBuffers = nullptr;
    PFN_vkResetCommandBuffer resetCommandBuffer = nullptr;
    PFN_vkBeginCommandBuffer beginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer endCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier cmdPipelineBarrier = nullptr;
    PFN_vkQueueSubmit queueSubmit = nullptr;
    PFN_vkCreateFence createFence = nullptr;
    PFN_vkDestroyFence destroyFence = nullptr;
    PFN_vkWaitForFences waitForFences = nullptr;
    PFN_vkResetFences resetFences = nullptr;
    PFN_vkQueueWaitIdle queueWaitIdle = nullptr;
    PFN_vkCmdCopyImage cmdCopyImage = nullptr;
    PFN_vkCmdBlitImage cmdBlitImage = nullptr;
    PFN_vkFreeCommandBuffers freeCommandBuffers = nullptr;

    // Presentation.
    PFN_vkCreateWin32SurfaceKHR createWin32Surface = nullptr;
    PFN_vkDestroySurfaceKHR destroySurface = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR getSurfaceSupport = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR getSurfaceCapabilities = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR getSurfaceFormats = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR getSurfacePresentModes = nullptr;
    PFN_vkCreateSwapchainKHR createSwapchain = nullptr;
    PFN_vkDestroySwapchainKHR destroySwapchain = nullptr;
    PFN_vkGetSwapchainImagesKHR getSwapchainImages = nullptr;
    PFN_vkAcquireNextImageKHR acquireNextImage = nullptr;
    PFN_vkQueuePresentKHR queuePresent = nullptr;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkQueue queue = VK_NULL_HANDLE;
    // Second queue of the same family for the presenter thread (== queue if the family has one).
    VkQueue presentQueue = VK_NULL_HANDLE;
    bool ngxInitialized = false;
    NVSDK_NGX_Parameter* capabilities = nullptr;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;

    uint32_t opticalFlowFamily = UINT32_MAX;
    std::vector<std::string> fgExtensionNames;
    std::string fgExtensionsMissing;
};

struct SharedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct Frame {
    SharedImage images[IRISDLSS_IMAGE_COUNT];
    VkSemaphore glDone = VK_NULL_HANDLE;
    VkSemaphore vkDone = VK_NULL_HANDLE;
    NVSDK_NGX_Handle* dlss = nullptr;
    NVSDK_NGX_Parameter* params = nullptr;
    bool fenceInFlight = false;
};

Vk g_vk;
Frame g_frame;

// Queue submissions are externally synchronized; only needed when the presenter shares the queue.
std::mutex g_queueMutex;

std::unique_lock<std::mutex> lockQueue(VkQueue queue) {
    std::unique_lock<std::mutex> lock(g_queueMutex, std::defer_lock);
    if (g_vk.presentQueue == g_vk.queue && queue == g_vk.queue) {
        lock.lock();
    }
    return lock;
}

VkResult submitTo(VkQueue queue, const VkSubmitInfo& info, VkFence fence) {
    auto lock = lockQueue(queue);
    return g_vk.queueSubmit(queue, 1, &info, fence);
}

void releaseFrameGeneration();

template <typename T>
bool load(T& fn, const char* name) {
    fn = reinterpret_cast<T>(g_vk.getInstanceProcAddr(g_vk.instance, name));
    return fn != nullptr;
}

template <typename T>
void loadDevice(T& fn, const char* name) {
    fn = reinterpret_cast<T>(g_vk.getDeviceProcAddr(g_vk.device, name));
}

std::string narrow(const wchar_t* text) {
    if (!text) {
        return {};
    }
    int size = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
    std::string out(size > 0 ? size - 1 : 0, '\0');
    if (size > 0) {
        WideCharToMultiByte(CP_UTF8, 0, text, -1, out.data(), size, nullptr, nullptr);
    }
    return out;
}

int32_t fail(int32_t status, const std::string& message) {
    g_message = message;
    return status;
}

std::string ngxError(const char* what, NVSDK_NGX_Result result) {
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), " (0x%08X) ", static_cast<unsigned>(result));
    return std::string(what) + buffer + narrow(GetNGXResultAsString(result));
}

bool hasExtension(const std::vector<VkExtensionProperties>& available, const char* name) {
    for (const auto& ext : available) {
        if (std::strcmp(ext.extensionName, name) == 0) {
            return true;
        }
    }
    return false;
}

}  // namespace

IRISDLSS_API const char* irisdlss_last_message() {
    return g_message.c_str();
}

IRISDLSS_API int32_t irisdlss_init(const uint8_t* deviceUuid, const wchar_t* dataPath) {
    if (g_vk.ngxInitialized) {
        return IRISDLSS_OK;
    }

    g_vk.loader = LoadLibraryW(L"vulkan-1.dll");
    if (!g_vk.loader) {
        return fail(IRISDLSS_NO_VULKAN, "vulkan-1.dll not found");
    }
    g_vk.getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        GetProcAddress(g_vk.loader, "vkGetInstanceProcAddr"));
    if (!g_vk.getInstanceProcAddr || !load(g_vk.createInstance, "vkCreateInstance")) {
        return fail(IRISDLSS_NO_VULKAN, "vkGetInstanceProcAddr unavailable");
    }

    // Instance: NGX's required extensions plus capability queries for external memory interop.
    unsigned int ngxInstanceExtCount = 0, ngxDeviceExtCount = 0;
    const char** ngxInstanceExts = nullptr;
    const char** ngxDeviceExts = nullptr;
    NVSDK_NGX_VULKAN_RequiredExtensions(&ngxInstanceExtCount, &ngxInstanceExts, &ngxDeviceExtCount,
        &ngxDeviceExts);
    std::vector<const char*> instanceExts(ngxInstanceExts, ngxInstanceExts + ngxInstanceExtCount);
    auto addUnique = [](std::vector<const char*>& list, const char* name) {
        for (const char* existing : list) {
            if (std::strcmp(existing, name) == 0) {
                return;
            }
        }
        list.push_back(name);
    };
    addUnique(instanceExts, VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
    addUnique(instanceExts, VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME);
    addUnique(instanceExts, VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME);
    // Presentation through our own swapchain (frame generation needs to control presentation).
    addUnique(instanceExts, VK_KHR_SURFACE_EXTENSION_NAME);
    addUnique(instanceExts, VK_KHR_WIN32_SURFACE_EXTENSION_NAME);

    // Frame generation's own extension requirements.
    static const wchar_t* fgPaths[] = {dataPath};
    static NVSDK_NGX_FeatureCommonInfo fgCommon{};
    fgCommon.PathListInfo.Path = fgPaths;
    fgCommon.PathListInfo.Length = 1;
    NVSDK_NGX_FeatureDiscoveryInfo fgDiscovery{};
    fgDiscovery.SDKVersion = NVSDK_NGX_Version_API;
    fgDiscovery.FeatureID = NVSDK_NGX_Feature_FrameGeneration;
    fgDiscovery.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    fgDiscovery.Identifier.v.ProjectDesc.ProjectId = kProjectId;
    fgDiscovery.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    fgDiscovery.Identifier.v.ProjectDesc.EngineVersion = kEngineVersion;
    fgDiscovery.ApplicationDataPath = dataPath;
    fgDiscovery.FeatureInfo = &fgCommon;
    uint32_t fgInstanceExtCount = 0;
    VkExtensionProperties* fgInstanceExts = nullptr;
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&fgDiscovery,
            &fgInstanceExtCount, &fgInstanceExts))) {
        for (uint32_t i = 0; i < fgInstanceExtCount; i++) {
            addUnique(instanceExts, fgInstanceExts[i].extensionName);
        }
    }

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Iris DLSS Bridge";
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    instanceInfo.pApplicationInfo = &app;
    instanceInfo.enabledExtensionCount = static_cast<uint32_t>(instanceExts.size());
    instanceInfo.ppEnabledExtensionNames = instanceExts.data();
    if (g_vk.createInstance(&instanceInfo, nullptr, &g_vk.instance) != VK_SUCCESS) {
        return fail(IRISDLSS_NO_VULKAN, "vkCreateInstance failed");
    }
    load(g_vk.destroyInstance, "vkDestroyInstance");
    load(g_vk.enumeratePhysicalDevices, "vkEnumeratePhysicalDevices");
    load(g_vk.getPhysicalDeviceProperties2, "vkGetPhysicalDeviceProperties2");
    load(g_vk.getQueueFamilyProperties, "vkGetPhysicalDeviceQueueFamilyProperties");
    load(g_vk.enumerateDeviceExtensions, "vkEnumerateDeviceExtensionProperties");
    load(g_vk.createDevice, "vkCreateDevice");
    load(g_vk.destroyDevice, "vkDestroyDevice");
    load(g_vk.getDeviceQueue, "vkGetDeviceQueue");
    load(g_vk.deviceWaitIdle, "vkDeviceWaitIdle");
    load(g_vk.getDeviceProcAddr, "vkGetDeviceProcAddr");
    load(g_vk.getMemoryProperties, "vkGetPhysicalDeviceMemoryProperties");
    load(g_vk.createWin32Surface, "vkCreateWin32SurfaceKHR");
    load(g_vk.destroySurface, "vkDestroySurfaceKHR");
    load(g_vk.getSurfaceSupport, "vkGetPhysicalDeviceSurfaceSupportKHR");
    load(g_vk.getSurfaceCapabilities, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    load(g_vk.getSurfaceFormats, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    load(g_vk.getSurfacePresentModes, "vkGetPhysicalDeviceSurfacePresentModesKHR");

    // Physical device: the one OpenGL runs on (same UUID), so memory can be shared.
    uint32_t count = 0;
    g_vk.enumeratePhysicalDevices(g_vk.instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    g_vk.enumeratePhysicalDevices(g_vk.instance, &count, devices.data());
    std::string deviceName;
    for (VkPhysicalDevice candidate : devices) {
        VkPhysicalDeviceIDProperties id{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        props.pNext = &id;
        g_vk.getPhysicalDeviceProperties2(candidate, &props);
        bool matches = deviceUuid ? std::memcmp(id.deviceUUID, deviceUuid, VK_UUID_SIZE) == 0
                                  : props.properties.vendorID == 0x10DE;
        if (matches) {
            g_vk.physicalDevice = candidate;
            deviceName = props.properties.deviceName;
            break;
        }
    }
    if (!g_vk.physicalDevice) {
        return fail(IRISDLSS_NO_MATCHING_GPU, "no Vulkan device matches the OpenGL GPU");
    }

    uint32_t familyCount = 0;
    g_vk.getQueueFamilyProperties(g_vk.physicalDevice, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    g_vk.getQueueFamilyProperties(g_vk.physicalDevice, &familyCount, families.data());
    bool foundQueue = false;
    for (uint32_t i = 0; i < familyCount; i++) {
        if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT))
            == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            g_vk.queueFamily = i;
            foundQueue = true;
            break;
        }
    }
    if (!foundQueue) {
        return fail(IRISDLSS_DEVICE_FAILED, "no graphics+compute queue");
    }

    // Device: NGX's extensions plus Win32 external memory / semaphores for the OpenGL interop.
    uint32_t extCount = 0;
    g_vk.enumerateDeviceExtensions(g_vk.physicalDevice, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> available(extCount);
    g_vk.enumerateDeviceExtensions(g_vk.physicalDevice, nullptr, &extCount, available.data());
    std::vector<const char*> deviceExts;
    for (unsigned int i = 0; i < ngxDeviceExtCount; i++) {
        if (hasExtension(available, ngxDeviceExts[i])) {
            deviceExts.push_back(ngxDeviceExts[i]);
        }
    }
    for (const char* ext : {VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME, VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
             VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME,
             VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME, VK_KHR_SWAPCHAIN_EXTENSION_NAME}) {
        if (!hasExtension(available, ext)) {
            return fail(IRISDLSS_DEVICE_FAILED, std::string("missing device extension ") + ext);
        }
        addUnique(deviceExts, ext);
    }
    uint32_t fgDeviceExtCount = 0;
    VkExtensionProperties* fgDeviceExts = nullptr;
    g_vk.fgExtensionsMissing.clear();
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(g_vk.instance,
            g_vk.physicalDevice, &fgDiscovery, &fgDeviceExtCount, &fgDeviceExts))) {
        for (uint32_t i = 0; i < fgDeviceExtCount; i++) {
            if (hasExtension(available, fgDeviceExts[i].extensionName)) {
                g_vk.fgExtensionNames.emplace_back(fgDeviceExts[i].extensionName);
            } else {
                g_vk.fgExtensionsMissing += std::string(fgDeviceExts[i].extensionName) + " ";
            }
        }
    }
    for (const std::string& ext : g_vk.fgExtensionNames) {
        addUnique(deviceExts, ext.c_str());
    }
    bool opticalFlow = hasExtension(available, VK_NV_OPTICAL_FLOW_EXTENSION_NAME);

    // Queues: the graphics+compute queue, plus an optical-flow queue if the GPU has one (DLSS-G).
    g_vk.opticalFlowFamily = UINT32_MAX;
    for (uint32_t i = 0; i < familyCount; i++) {
        if (i != g_vk.queueFamily && (families[i].queueFlags & VK_QUEUE_OPTICAL_FLOW_BIT_NV)) {
            g_vk.opticalFlowFamily = i;
            break;
        }
    }
    float priorities[2] = {1.0f, 1.0f};
    uint32_t mainQueueCount = families[g_vk.queueFamily].queueCount >= 2 ? 2 : 1;
    VkDeviceQueueCreateInfo queueInfos[2]{};
    queueInfos[0].sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfos[0].queueFamilyIndex = g_vk.queueFamily;
    queueInfos[0].queueCount = mainQueueCount;
    queueInfos[0].pQueuePriorities = priorities;
    uint32_t queueInfoCount = 1;
    if (g_vk.opticalFlowFamily != UINT32_MAX) {
        queueInfos[1] = queueInfos[0];
        queueInfos[1].queueFamilyIndex = g_vk.opticalFlowFamily;
        queueInfos[1].queueCount = 1;
        queueInfoCount = 2;
    }
    VkPhysicalDeviceOpticalFlowFeaturesNV opticalFlowFeatures{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV};
    opticalFlowFeatures.opticalFlow = VK_TRUE;
    VkPhysicalDeviceVulkan13Features features13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    features13.synchronization2 = VK_TRUE;
    features13.pNext = opticalFlow ? &opticalFlowFeatures : nullptr;
    if (opticalFlow) {
        addUnique(deviceExts, VK_NV_OPTICAL_FLOW_EXTENSION_NAME);
    }
    VkPhysicalDeviceVulkan12Features features12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    features12.bufferDeviceAddress = VK_TRUE;
    features12.timelineSemaphore = VK_TRUE;
    features12.pNext = &features13;
    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.pNext = &features12;
    deviceInfo.queueCreateInfoCount = queueInfoCount;
    deviceInfo.pQueueCreateInfos = queueInfos;
    deviceInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExts.size());
    deviceInfo.ppEnabledExtensionNames = deviceExts.data();
    if (g_vk.createDevice(g_vk.physicalDevice, &deviceInfo, nullptr, &g_vk.device) != VK_SUCCESS) {
        return fail(IRISDLSS_DEVICE_FAILED, "vkCreateDevice failed");
    }
    g_vk.getDeviceQueue(g_vk.device, g_vk.queueFamily, 0, &g_vk.queue);
    g_vk.getDeviceQueue(g_vk.device, g_vk.queueFamily, mainQueueCount - 1, &g_vk.presentQueue);
    loadDevice(g_vk.createImage, "vkCreateImage");
    loadDevice(g_vk.destroyImage, "vkDestroyImage");
    loadDevice(g_vk.getImageMemoryRequirements, "vkGetImageMemoryRequirements");
    loadDevice(g_vk.allocateMemory, "vkAllocateMemory");
    loadDevice(g_vk.freeMemory, "vkFreeMemory");
    loadDevice(g_vk.bindImageMemory, "vkBindImageMemory");
    loadDevice(g_vk.createImageView, "vkCreateImageView");
    loadDevice(g_vk.destroyImageView, "vkDestroyImageView");
    loadDevice(g_vk.getMemoryWin32Handle, "vkGetMemoryWin32HandleKHR");
    loadDevice(g_vk.createSemaphore, "vkCreateSemaphore");
    loadDevice(g_vk.destroySemaphore, "vkDestroySemaphore");
    loadDevice(g_vk.getSemaphoreWin32Handle, "vkGetSemaphoreWin32HandleKHR");
    loadDevice(g_vk.createCommandPool, "vkCreateCommandPool");
    loadDevice(g_vk.destroyCommandPool, "vkDestroyCommandPool");
    loadDevice(g_vk.allocateCommandBuffers, "vkAllocateCommandBuffers");
    loadDevice(g_vk.resetCommandBuffer, "vkResetCommandBuffer");
    loadDevice(g_vk.beginCommandBuffer, "vkBeginCommandBuffer");
    loadDevice(g_vk.endCommandBuffer, "vkEndCommandBuffer");
    loadDevice(g_vk.cmdPipelineBarrier, "vkCmdPipelineBarrier");
    loadDevice(g_vk.queueSubmit, "vkQueueSubmit");
    loadDevice(g_vk.createFence, "vkCreateFence");
    loadDevice(g_vk.destroyFence, "vkDestroyFence");
    loadDevice(g_vk.waitForFences, "vkWaitForFences");
    loadDevice(g_vk.resetFences, "vkResetFences");
    loadDevice(g_vk.queueWaitIdle, "vkQueueWaitIdle");
    loadDevice(g_vk.cmdCopyImage, "vkCmdCopyImage");
    loadDevice(g_vk.cmdBlitImage, "vkCmdBlitImage");
    loadDevice(g_vk.freeCommandBuffers, "vkFreeCommandBuffers");
    loadDevice(g_vk.createSwapchain, "vkCreateSwapchainKHR");
    loadDevice(g_vk.destroySwapchain, "vkDestroySwapchainKHR");
    loadDevice(g_vk.getSwapchainImages, "vkGetSwapchainImagesKHR");
    loadDevice(g_vk.acquireNextImage, "vkAcquireNextImageKHR");
    loadDevice(g_vk.queuePresent, "vkQueuePresentKHR");

    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = g_vk.queueFamily;
    VkCommandBufferAllocateInfo allocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (g_vk.createCommandPool(g_vk.device, &poolInfo, nullptr, &g_vk.commandPool) != VK_SUCCESS
        || (allocInfo.commandPool = g_vk.commandPool,
            g_vk.allocateCommandBuffers(g_vk.device, &allocInfo, &g_vk.commandBuffer) != VK_SUCCESS)
        || g_vk.createFence(g_vk.device, &fenceInfo, nullptr, &g_vk.fence) != VK_SUCCESS) {
        return fail(IRISDLSS_DEVICE_FAILED, "command buffer setup failed");
    }

    // NGX looks for nvngx_dlss.dll next to the executable (java.exe) by default; add dataPath.
    const wchar_t* searchPaths[] = {dataPath};
    NVSDK_NGX_FeatureCommonInfo featureInfo{};
    featureInfo.PathListInfo.Path = searchPaths;
    featureInfo.PathListInfo.Length = 1;
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Init_with_ProjectID(kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM,
        kEngineVersion, dataPath, g_vk.instance, g_vk.physicalDevice, g_vk.device, g_vk.getInstanceProcAddr,
        g_vk.getDeviceProcAddr, &featureInfo);
    if (NVSDK_NGX_FAILED(result)) {
        return fail(IRISDLSS_NGX_INIT_FAILED, ngxError("NGX init failed", result));
    }
    g_vk.ngxInitialized = true;

    result = NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_vk.capabilities);
    if (NVSDK_NGX_FAILED(result)) {
        return fail(IRISDLSS_NGX_INIT_FAILED, ngxError("NGX capability query failed", result));
    }
    int needsDriver = 0;
    unsigned int minMajor = 0, minMinor = 0;
    NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDriver);
    NVSDK_NGX_Parameter_GetUI(g_vk.capabilities, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, &minMajor);
    NVSDK_NGX_Parameter_GetUI(g_vk.capabilities, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, &minMinor);
    if (needsDriver) {
        return fail(IRISDLSS_DRIVER_TOO_OLD, "DLSS needs driver " + std::to_string(minMajor) + "."
            + std::to_string(minMinor) + " or newer");
    }
    int available_ = 0;
    result = NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_SuperSampling_Available, &available_);
    if (NVSDK_NGX_FAILED(result) || !available_) {
        int initResult = 0;
        NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initResult);
        return fail(IRISDLSS_DLSS_UNAVAILABLE,
            ngxError("DLSS Super Resolution unavailable", static_cast<NVSDK_NGX_Result>(initResult)));
    }

    g_message = "DLSS Super Resolution available on " + deviceName;
    return IRISDLSS_OK;
}

namespace {

uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties props;
    g_vk.getMemoryProperties(g_vk.physicalDevice, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool createSharedImage(SharedImage& out, VkFormat format, uint32_t width, uint32_t height,
    IrisDlssSharedImage& exported) {
    VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.pNext = &external;
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {width, height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
        | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (g_vk.createImage(g_vk.device, &info, nullptr, &out.image) != VK_SUCCESS) {
        return false;
    }
    VkMemoryRequirements requirements;
    g_vk.getImageMemoryRequirements(g_vk.device, out.image, &requirements);

    VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = out.image;
    VkExportMemoryAllocateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
    exportInfo.pNext = &dedicated;
    exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.pNext = &exportInfo;
    alloc.allocationSize = requirements.size;
    alloc.memoryTypeIndex = findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (alloc.memoryTypeIndex == UINT32_MAX
        || g_vk.allocateMemory(g_vk.device, &alloc, nullptr, &out.memory) != VK_SUCCESS
        || g_vk.bindImageMemory(g_vk.device, out.image, out.memory, 0) != VK_SUCCESS) {
        return false;
    }

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = out.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (g_vk.createImageView(g_vk.device, &viewInfo, nullptr, &out.view) != VK_SUCCESS) {
        return false;
    }

    VkMemoryGetWin32HandleInfoKHR handleInfo{VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR};
    handleInfo.memory = out.memory;
    handleInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    HANDLE handle = nullptr;
    if (g_vk.getMemoryWin32Handle(g_vk.device, &handleInfo, &handle) != VK_SUCCESS) {
        return false;
    }
    out.format = format;
    out.width = width;
    out.height = height;
    exported.memoryHandle = reinterpret_cast<uint64_t>(handle);
    exported.memorySize = requirements.size;
    exported.width = width;
    exported.height = height;
    return true;
}

bool createSharedSemaphore(VkSemaphore& out, uint64_t& handleOut) {
    VkExportSemaphoreCreateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    info.pNext = &exportInfo;
    if (g_vk.createSemaphore(g_vk.device, &info, nullptr, &out) != VK_SUCCESS) {
        return false;
    }
    VkSemaphoreGetWin32HandleInfoKHR handleInfo{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
    handleInfo.semaphore = out;
    handleInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    HANDLE handle = nullptr;
    if (g_vk.getSemaphoreWin32Handle(g_vk.device, &handleInfo, &handle) != VK_SUCCESS) {
        return false;
    }
    handleOut = reinterpret_cast<uint64_t>(handle);
    return true;
}

void waitForPreviousSubmit() {
    if (g_frame.fenceInFlight) {
        g_vk.waitForFences(g_vk.device, 1, &g_vk.fence, VK_TRUE, UINT64_MAX);
        g_vk.resetFences(g_vk.device, 1, &g_vk.fence);
        g_frame.fenceInFlight = false;
    }
}

VkImageMemoryBarrier barrier(VkImage image, VkImageLayout from, VkImageLayout to,
    uint32_t srcFamily = VK_QUEUE_FAMILY_IGNORED, uint32_t dstFamily = VK_QUEUE_FAMILY_IGNORED) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    b.dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = srcFamily;
    b.dstQueueFamilyIndex = dstFamily;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return b;
}

VkImageMemoryBarrier barrier(const SharedImage& image, VkImageLayout from, VkImageLayout to,
    uint32_t srcFamily, uint32_t dstFamily) {
    return barrier(image.image, from, to, srcFamily, dstFamily);
}

}  // namespace

IRISDLSS_API void irisdlss_destroy_resources() {
    if (!g_vk.device) {
        return;
    }
    waitForPreviousSubmit();
    {
        auto lock = lockQueue(g_vk.queue);
        g_vk.queueWaitIdle(g_vk.queue);
    }
    // Frame generation reads these images (and is created for their size).
    releaseFrameGeneration();
    if (g_frame.dlss) {
        NVSDK_NGX_VULKAN_ReleaseFeature(g_frame.dlss);
        g_frame.dlss = nullptr;
    }
    if (g_frame.params) {
        NVSDK_NGX_VULKAN_DestroyParameters(g_frame.params);
        g_frame.params = nullptr;
    }
    for (SharedImage& image : g_frame.images) {
        if (image.view) g_vk.destroyImageView(g_vk.device, image.view, nullptr);
        if (image.image) g_vk.destroyImage(g_vk.device, image.image, nullptr);
        if (image.memory) g_vk.freeMemory(g_vk.device, image.memory, nullptr);
        image = SharedImage{};
    }
    if (g_frame.glDone) g_vk.destroySemaphore(g_vk.device, g_frame.glDone, nullptr);
    if (g_frame.vkDone) g_vk.destroySemaphore(g_vk.device, g_frame.vkDone, nullptr);
    g_frame.glDone = g_frame.vkDone = VK_NULL_HANDLE;
}

IRISDLSS_API int32_t irisdlss_create_resources(uint32_t renderWidth, uint32_t renderHeight,
    uint32_t outWidth, uint32_t outHeight, int32_t quality, int32_t depthInverted, int32_t preset, IrisDlssResources* out) {
    if (!g_vk.ngxInitialized) {
        return fail(IRISDLSS_NGX_INIT_FAILED, "not initialized");
    }
    irisdlss_destroy_resources();
    std::memset(out, 0, sizeof(*out));

    const VkFormat formats[IRISDLSS_IMAGE_COUNT] = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R32_SFLOAT,
        VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM};
    for (int i = 0; i < IRISDLSS_IMAGE_COUNT; i++) {
        bool output = i == IRISDLSS_IMAGE_COUNT - 1;
        if (!createSharedImage(g_frame.images[i], formats[i], output ? outWidth : renderWidth,
                output ? outHeight : renderHeight, out->images[i])) {
            return fail(IRISDLSS_DEVICE_FAILED, "shared image creation failed (" + std::to_string(i) + ")");
        }
    }
    if (!createSharedSemaphore(g_frame.glDone, out->glDoneSemaphore)
        || !createSharedSemaphore(g_frame.vkDone, out->vkDoneSemaphore)) {
        return fail(IRISDLSS_DEVICE_FAILED, "shared semaphore creation failed");
    }

    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_AllocateParameters(&g_frame.params);
    if (NVSDK_NGX_FAILED(result)) {
        return fail(IRISDLSS_NGX_INIT_FAILED, ngxError("NGX parameter allocation failed", result));
    }
    // Model preset (NVSDK_NGX_DLSS_Hint_Render_Preset): 0 = driver default, 11 = K (transformer).
    for (const char* key : {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality}) {
        NVSDK_NGX_Parameter_SetUI(g_frame.params, key, static_cast<unsigned int>(preset));
    }

    // Move every image into GENERAL and hand it to OpenGL (external queue family) once, then create
    // the DLSS feature in the same submission.
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(g_vk.commandBuffer, 0);
    g_vk.beginCommandBuffer(g_vk.commandBuffer, &begin);
    VkImageMemoryBarrier barriers[IRISDLSS_IMAGE_COUNT];
    for (int i = 0; i < IRISDLSS_IMAGE_COUNT; i++) {
        barriers[i] = barrier(g_frame.images[i], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    }
    g_vk.cmdPipelineBarrier(g_vk.commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, IRISDLSS_IMAGE_COUNT, barriers);

    NVSDK_NGX_DLSS_Create_Params create{};
    create.Feature.InWidth = renderWidth;
    create.Feature.InHeight = renderHeight;
    create.Feature.InTargetWidth = outWidth;
    create.Feature.InTargetHeight = outHeight;
    create.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(quality);
    create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes
        | (depthInverted ? NVSDK_NGX_DLSS_Feature_Flags_DepthInverted : 0);
    result = NGX_VULKAN_CREATE_DLSS_EXT1(g_vk.device, g_vk.commandBuffer, 1, 1, &g_frame.dlss, g_frame.params, &create);
    g_vk.endCommandBuffer(g_vk.commandBuffer);
    if (NVSDK_NGX_FAILED(result)) {
        return fail(IRISDLSS_DLSS_UNAVAILABLE, ngxError("DLSS feature creation failed", result));
    }
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g_vk.commandBuffer;
    if (submitTo(g_vk.queue, submit, g_vk.fence) != VK_SUCCESS) {
        return fail(IRISDLSS_DEVICE_FAILED, "initial submit failed");
    }
    g_frame.fenceInFlight = true;
    waitForPreviousSubmit();

    g_message = "DLSS " + std::to_string(renderWidth) + "x" + std::to_string(renderHeight) + " -> "
        + std::to_string(outWidth) + "x" + std::to_string(outHeight);
    return IRISDLSS_OK;
}

IRISDLSS_API int32_t irisdlss_evaluate(float jitterX, float jitterY, float mvScaleX, float mvScaleY,
    int32_t reset, float sharpness) {
    if (!g_frame.dlss) {
        return fail(IRISDLSS_DLSS_UNAVAILABLE, "no DLSS feature");
    }
    waitForPreviousSubmit();

    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(g_vk.commandBuffer, 0);
    g_vk.beginCommandBuffer(g_vk.commandBuffer, &begin);

    // Acquire from OpenGL: inputs become read-only, output stays GENERAL for storage writes.
    VkImageMemoryBarrier acquire[IRISDLSS_IMAGE_COUNT];
    for (int i = 0; i < IRISDLSS_IMAGE_COUNT; i++) {
        bool output = i == IRISDLSS_IMAGE_COUNT - 1;
        acquire[i] = barrier(g_frame.images[i], VK_IMAGE_LAYOUT_GENERAL,
            output ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_QUEUE_FAMILY_EXTERNAL, g_vk.queueFamily);
    }
    g_vk.cmdPipelineBarrier(g_vk.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, IRISDLSS_IMAGE_COUNT, acquire);

    auto resource = [](int index, bool readWrite) {
        const SharedImage& image = g_frame.images[index];
        return NVSDK_NGX_Create_ImageView_Resource_VK(image.view, image.image,
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, image.format, image.width, image.height, readWrite);
    };
    NVSDK_NGX_Resource_VK color = resource(0, false);
    NVSDK_NGX_Resource_VK depth = resource(1, false);
    NVSDK_NGX_Resource_VK motion = resource(2, false);
    NVSDK_NGX_Resource_VK output = resource(3, true);

    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
    eval.Feature.pInColor = &color;
    eval.Feature.pInOutput = &output;
    eval.Feature.InSharpness = sharpness;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &motion;
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InRenderSubrectDimensions = {g_frame.images[0].width, g_frame.images[0].height};
    eval.InReset = reset;
    eval.InMVScaleX = mvScaleX;
    eval.InMVScaleY = mvScaleY;
    NVSDK_NGX_Result result = NGX_VULKAN_EVALUATE_DLSS_EXT(g_vk.commandBuffer, g_frame.dlss, g_frame.params, &eval);

    // Release back to OpenGL in GENERAL.
    VkImageMemoryBarrier release[IRISDLSS_IMAGE_COUNT];
    for (int i = 0; i < IRISDLSS_IMAGE_COUNT; i++) {
        bool outputImage = i == IRISDLSS_IMAGE_COUNT - 1;
        release[i] = barrier(g_frame.images[i],
            outputImage ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL, g_vk.queueFamily, VK_QUEUE_FAMILY_EXTERNAL);
    }
    g_vk.cmdPipelineBarrier(g_vk.commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, IRISDLSS_IMAGE_COUNT, release);
    g_vk.endCommandBuffer(g_vk.commandBuffer);

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &g_frame.glDone;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g_vk.commandBuffer;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &g_frame.vkDone;
    if (submitTo(g_vk.queue, submit, g_vk.fence) != VK_SUCCESS) {
        return fail(IRISDLSS_DEVICE_FAILED, "evaluate submit failed");
    }
    g_frame.fenceInFlight = true;
    if (NVSDK_NGX_FAILED(result)) {
        return fail(IRISDLSS_DLSS_UNAVAILABLE, ngxError("DLSS evaluate failed", result));
    }
    return IRISDLSS_OK;
}

IRISDLSS_API int32_t irisdlss_probe_frame_generation(uint32_t width, uint32_t height) {
    if (!g_vk.capabilities) {
        return fail(IRISDLSS_NGX_INIT_FAILED, "not initialized");
    }
    int available = 0, needsDriver = 0, initResult = 0;
    NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_FrameGeneration_Available, &available);
    NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_FrameGeneration_NeedsUpdatedDriver, &needsDriver);
    NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_FrameGeneration_FeatureInitResult, &initResult);
    std::string info = "FG available=" + std::to_string(available) + " needsDriver=" + std::to_string(needsDriver)
        + " initResult=" + ngxError("", static_cast<NVSDK_NGX_Result>(initResult))
        + " opticalFlowQueue=" + (g_vk.opticalFlowFamily != UINT32_MAX ? "yes" : "no")
        + " missingExt=[" + g_vk.fgExtensionsMissing + "]";

    NVSDK_NGX_Parameter* params = nullptr;
    NVSDK_NGX_VULKAN_AllocateParameters(&params);
    NVSDK_NGX_DLSSG_Create_Params create{};
    create.Width = width;
    create.Height = height;
    create.NativeBackbufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    create.RenderWidth = width;
    create.RenderHeight = height;
    NVSDK_NGX_Handle* handle = nullptr;
    waitForPreviousSubmit();
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(g_vk.commandBuffer, 0);
    g_vk.beginCommandBuffer(g_vk.commandBuffer, &begin);
    NVSDK_NGX_Result result = NGX_VK_CREATE_DLSSG(g_vk.commandBuffer, 1, 1, &handle, params, &create);
    g_vk.endCommandBuffer(g_vk.commandBuffer);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g_vk.commandBuffer;
    submitTo(g_vk.queue, submit, g_vk.fence);
    g_frame.fenceInFlight = true;
    waitForPreviousSubmit();
    if (handle) {
        NVSDK_NGX_VULKAN_ReleaseFeature(handle);
    }
    NVSDK_NGX_VULKAN_DestroyParameters(params);
    g_message = info + " | create: " + ngxError("", result);
    return NVSDK_NGX_FAILED(result) ? IRISDLSS_DLSS_UNAVAILABLE : IRISDLSS_OK;
}

IRISDLSS_API int32_t irisdlss_optimal_settings(uint32_t outWidth, uint32_t outHeight, int32_t quality,
    uint32_t* renderWidth, uint32_t* renderHeight, float* sharpness) {
    if (!g_vk.capabilities) {
        return -1;
    }
    unsigned int maxW, maxH, minW, minH;
    NVSDK_NGX_Result result = NGX_DLSS_GET_OPTIMAL_SETTINGS(g_vk.capabilities, outWidth, outHeight,
        static_cast<NVSDK_NGX_PerfQuality_Value>(quality), renderWidth, renderHeight, &maxW, &maxH, &minW,
        &minH, sharpness);
    return NVSDK_NGX_FAILED(result) ? -1 : 0;
}

IRISDLSS_API void irisdlss_shutdown() {
    irisdlss_presenter_destroy();
    irisdlss_destroy_resources();
    if (g_vk.device && g_vk.deviceWaitIdle) {
        g_vk.deviceWaitIdle(g_vk.device);
    }
    if (g_vk.fence) {
        g_vk.destroyFence(g_vk.device, g_vk.fence, nullptr);
        g_vk.fence = VK_NULL_HANDLE;
    }
    if (g_vk.commandPool) {
        g_vk.destroyCommandPool(g_vk.device, g_vk.commandPool, nullptr);
        g_vk.commandPool = VK_NULL_HANDLE;
    }
    if (g_vk.capabilities) {
        NVSDK_NGX_VULKAN_DestroyParameters(g_vk.capabilities);
        g_vk.capabilities = nullptr;
    }
    if (g_vk.ngxInitialized) {
        NVSDK_NGX_VULKAN_Shutdown1(g_vk.device);
        g_vk.ngxInitialized = false;
    }
    if (g_vk.device) {
        g_vk.destroyDevice(g_vk.device, nullptr);
        g_vk.device = VK_NULL_HANDLE;
    }
    if (g_vk.instance) {
        g_vk.destroyInstance(g_vk.instance, nullptr);
        g_vk.instance = VK_NULL_HANDLE;
    }
}

// ---- Presentation -------------------------------------------------------------------------------
// The finished frame leaves through a Vulkan swapchain on a child window that covers the game
// window; OpenGL no longer swaps. With frame generation DLSS-G builds the frame between the previous
// and the current one: the presenter thread shows it first and the real frame half a frame later.

namespace {

constexpr int kSlotCount = 3;
constexpr int kBlitCount = 4;
constexpr VkImageSubresourceRange kColorRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

struct LocalImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

struct PresentSlot {
    LocalImage real;
    LocalImage interp;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkSemaphore realReady = VK_NULL_HANDLE;    // waited by the blit of the real frame
    VkSemaphore interpReady = VK_NULL_HANDLE;  // waited by the blit of the generated frame
    bool fenceInFlight = false;
    bool busy = false;  // queued for / shown by the presenter thread (guarded by Presenter::mutex)
};

struct Job {
    int slot;
    bool interpolated;
};

struct Presenter {
    HWND window = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    bool externalSync = true;

    SharedImage frame;
    VkSemaphore ready = VK_NULL_HANDLE;
    VkSemaphore consumed = VK_NULL_HANDLE;

    PresentSlot slots[kSlotCount];
    int nextSlot = 0;

    // Owned by the presenter thread once it runs.
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkExtent2D extent{};
    std::vector<VkImage> swapImages;
    std::vector<VkSemaphore> blitDone;  // per swapchain image
    VkCommandPool blitPool = VK_NULL_HANDLE;
    VkCommandBuffer blitBuffers[kBlitCount]{};
    VkFence blitFences[kBlitCount]{};
    VkSemaphore acquired[kBlitCount]{};
    bool blitInFlight[kBlitCount]{};
    int nextBlit = 0;
    HANDLE timer = nullptr;

    std::thread thread;
    std::mutex mutex;
    std::condition_variable cv;
    std::deque<Job> jobs;
    bool stop = false;
    std::atomic<bool> recreate{false};
    std::atomic<bool> vsync{true};
    std::atomic<bool> threadFailed{false};
    std::string threadError;

    // Frame generation (render thread).
    NVSDK_NGX_Handle* fg = nullptr;
    NVSDK_NGX_Parameter* fgParams = nullptr;
    bool fgFailed = false;

    // Pacing and stats.
    double frequency = 1.0;
    double lastFrame = 0.0;
    std::atomic<double> frameInterval{1.0 / 60.0};
    std::atomic<uint32_t> shownCount{0};
    std::atomic<uint32_t> generatedCount{0};
    double statsStart = 0.0;
    float fps = 0.0f;
    bool fgRecently = false;

    // Process exit without irisdlss_presenter_destroy: a joinable std::thread would terminate().
    ~Presenter() {
        if (thread.joinable()) {
            thread.detach();
        }
    }
};

Presenter g_present;

double now() {
    LARGE_INTEGER t;
    QueryPerformanceCounter(&t);
    return static_cast<double>(t.QuadPart) / g_present.frequency;
}

bool createLocalImage(LocalImage& out, uint32_t width, uint32_t height) {
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = VK_FORMAT_R8G8B8A8_UNORM;
    info.extent = {width, height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
        | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (g_vk.createImage(g_vk.device, &info, nullptr, &out.image) != VK_SUCCESS) {
        return false;
    }
    VkMemoryRequirements requirements;
    g_vk.getImageMemoryRequirements(g_vk.device, out.image, &requirements);
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = requirements.size;
    alloc.memoryTypeIndex = findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (alloc.memoryTypeIndex == UINT32_MAX
        || g_vk.allocateMemory(g_vk.device, &alloc, nullptr, &out.memory) != VK_SUCCESS
        || g_vk.bindImageMemory(g_vk.device, out.image, out.memory, 0) != VK_SUCCESS) {
        return false;
    }
    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = out.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = info.format;
    viewInfo.subresourceRange = kColorRange;
    return g_vk.createImageView(g_vk.device, &viewInfo, nullptr, &out.view) == VK_SUCCESS;
}

void destroyLocalImage(LocalImage& image) {
    if (image.view) g_vk.destroyImageView(g_vk.device, image.view, nullptr);
    if (image.image) g_vk.destroyImage(g_vk.device, image.image, nullptr);
    if (image.memory) g_vk.freeMemory(g_vk.device, image.memory, nullptr);
    image = LocalImage{};
}

VkSemaphore createSemaphore() {
    VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkSemaphore semaphore = VK_NULL_HANDLE;
    g_vk.createSemaphore(g_vk.device, &info, nullptr, &semaphore);
    return semaphore;
}

VkFence createFence() {
    VkFenceCreateInfo info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence = VK_NULL_HANDLE;
    g_vk.createFence(g_vk.device, &info, nullptr, &fence);
    return fence;
}

// Input-transparent child window: mouse and keyboard keep going to the game window.
LRESULT CALLBACK presentWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_NCHITTEST) {
        return HTTRANSPARENT;
    }
    if (message == WM_ERASEBKGND) {
        return 1;
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

HWND createPresentWindow(HWND parent, uint32_t width, uint32_t height) {
    static bool registered = false;
    HINSTANCE instance = GetModuleHandleW(nullptr);
    if (!registered) {
        WNDCLASSEXW wc{sizeof(wc)};
        wc.lpfnWndProc = presentWindowProc;
        wc.hInstance = instance;
        wc.lpszClassName = L"IrisDlssPresent";
        registered = RegisterClassExW(&wc) != 0;
    }
    return CreateWindowExW(WS_EX_NOPARENTNOTIFY, L"IrisDlssPresent", L"",
        WS_CHILD | WS_VISIBLE | WS_DISABLED | WS_CLIPSIBLINGS, 0, 0, static_cast<int>(width),
        static_cast<int>(height), parent, nullptr, instance, nullptr);
}

void setThreadError(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_present.mutex);
    g_present.threadError = message;
    g_present.threadFailed = true;
}

void waitPresentQueueIdle() {
    auto lock = lockQueue(g_vk.presentQueue);
    g_vk.queueWaitIdle(g_vk.presentQueue);
}

// Presenter thread (or before it starts). False with an empty error if the window has no area.
bool createSwapchain(std::string& error) {
    VkSurfaceCapabilitiesKHR caps;
    if (g_vk.getSurfaceCapabilities(g_vk.physicalDevice, g_present.surface, &caps) != VK_SUCCESS) {
        error = "surface capabilities query failed";
        return false;
    }
    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent = {g_present.width, g_present.height};
    }
    if (extent.width == 0 || extent.height == 0) {
        return false;
    }
    if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
        error = "swapchain cannot be a transfer destination";
        return false;
    }

    uint32_t count = 0;
    g_vk.getSurfaceFormats(g_vk.physicalDevice, g_present.surface, &count, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(count);
    g_vk.getSurfaceFormats(g_vk.physicalDevice, g_present.surface, &count, formats.data());
    if (formats.empty()) {
        error = "surface has no formats";
        return false;
    }
    VkSurfaceFormatKHR format = formats[0];
    for (const auto& candidate : formats) {
        // UNORM, not SRGB: the frame is already display-encoded, exactly as OpenGL showed it.
        if ((candidate.format == VK_FORMAT_B8G8R8A8_UNORM || candidate.format == VK_FORMAT_R8G8B8A8_UNORM)
            && candidate.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            format = candidate;
            break;
        }
    }

    g_vk.getSurfacePresentModes(g_vk.physicalDevice, g_present.surface, &count, nullptr);
    std::vector<VkPresentModeKHR> modes(count);
    g_vk.getSurfacePresentModes(g_vk.physicalDevice, g_present.surface, &count, modes.data());
    auto has = [&](VkPresentModeKHR mode) {
        for (VkPresentModeKHR m : modes) {
            if (m == mode) return true;
        }
        return false;
    };
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    if (!g_present.vsync) {
        mode = has(VK_PRESENT_MODE_IMMEDIATE_KHR) ? VK_PRESENT_MODE_IMMEDIATE_KHR
            : has(VK_PRESENT_MODE_MAILBOX_KHR)    ? VK_PRESENT_MODE_MAILBOX_KHR
                                                  : VK_PRESENT_MODE_FIFO_KHR;
    }

    uint32_t imageCount = caps.minImageCount < 3 ? 3 : caps.minImageCount;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }
    VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = g_present.surface;
    info.minImageCount = imageCount;
    info.imageFormat = format.format;
    info.imageColorSpace = format.colorSpace;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    info.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    info.presentMode = mode;
    info.clipped = VK_TRUE;
    info.oldSwapchain = g_present.swapchain;

    waitPresentQueueIdle();
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkResult result = g_vk.createSwapchain(g_vk.device, &info, nullptr, &swapchain);
    if (g_present.swapchain) {
        g_vk.destroySwapchain(g_vk.device, g_present.swapchain, nullptr);
        g_present.swapchain = VK_NULL_HANDLE;
    }
    for (VkSemaphore semaphore : g_present.blitDone) {
        g_vk.destroySemaphore(g_vk.device, semaphore, nullptr);
    }
    g_present.blitDone.clear();
    g_present.swapImages.clear();
    if (result != VK_SUCCESS) {
        error = "vkCreateSwapchainKHR failed (" + std::to_string(result) + ")";
        return false;
    }
    g_present.swapchain = swapchain;
    g_present.extent = extent;
    g_vk.getSwapchainImages(g_vk.device, swapchain, &count, nullptr);
    g_present.swapImages.resize(count);
    g_vk.getSwapchainImages(g_vk.device, swapchain, &count, g_present.swapImages.data());
    for (uint32_t i = 0; i < count; i++) {
        g_present.blitDone.push_back(createSemaphore());
    }
    return true;
}

int takeBlitSlot() {
    int index = g_present.nextBlit;
    g_present.nextBlit = (index + 1) % kBlitCount;
    if (g_present.blitInFlight[index]) {
        g_vk.waitForFences(g_vk.device, 1, &g_present.blitFences[index], VK_TRUE, UINT64_MAX);
        g_vk.resetFences(g_vk.device, 1, &g_present.blitFences[index]);
        g_present.blitInFlight[index] = false;
    }
    return index;
}

// Every signal of a slot semaphore needs exactly one wait, even for a frame that is not shown.
void consumeSemaphore(VkSemaphore semaphore) {
    int b = takeBlitSlot();
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &semaphore;
    submit.pWaitDstStageMask = &stage;
    if (submitTo(g_vk.presentQueue, submit, g_present.blitFences[b]) == VK_SUCCESS) {
        g_present.blitInFlight[b] = true;
    }
}

void showImage(const LocalImage& source, VkSemaphore sourceReady) {
    if (g_present.recreate.exchange(false) || !g_present.swapchain) {
        std::string error;
        if (!createSwapchain(error)) {
            if (!error.empty()) {
                setThreadError(error);
            }
            g_present.recreate = true;
            consumeSemaphore(sourceReady);
            return;
        }
    }
    int b = takeBlitSlot();
    uint32_t index = 0;
    VkResult result = g_vk.acquireNextImage(g_vk.device, g_present.swapchain, UINT64_MAX, g_present.acquired[b],
        VK_NULL_HANDLE, &index);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        g_present.recreate = true;
        consumeSemaphore(sourceReady);
        return;
    }

    VkCommandBuffer cmd = g_present.blitBuffers[b];
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(cmd, 0);
    g_vk.beginCommandBuffer(cmd, &begin);
    VkImage target = g_present.swapImages[index];
    VkImageMemoryBarrier toTransfer = barrier(target, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
        nullptr, 1, &toTransfer);
    // OpenGL images start at the bottom row: flip while copying.
    VkImageBlit region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.srcOffsets[0] = {0, static_cast<int32_t>(g_present.height), 0};
    region.srcOffsets[1] = {static_cast<int32_t>(g_present.width), 0, 1};
    region.dstSubresource = region.srcSubresource;
    region.dstOffsets[1] = {static_cast<int32_t>(g_present.extent.width),
        static_cast<int32_t>(g_present.extent.height), 1};
    bool sameSize = g_present.extent.width == g_present.width && g_present.extent.height == g_present.height;
    g_vk.cmdBlitImage(cmd, source.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
    VkImageMemoryBarrier toPresent = barrier(target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
        nullptr, 0, nullptr, 1, &toPresent);
    g_vk.endCommandBuffer(cmd);

    VkSemaphore waits[2] = {g_present.acquired[b], sourceReady};
    VkPipelineStageFlags stages[2] = {VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 2;
    submit.pWaitSemaphores = waits;
    submit.pWaitDstStageMask = stages;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &g_present.blitDone[index];
    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &g_present.blitDone[index];
    present.swapchainCount = 1;
    present.pSwapchains = &g_present.swapchain;
    present.pImageIndices = &index;
    {
        auto lock = lockQueue(g_vk.presentQueue);
        if (g_vk.queueSubmit(g_vk.presentQueue, 1, &submit, g_present.blitFences[b]) != VK_SUCCESS) {
            lock.unlock();
            setThreadError("present blit submit failed");
            return;
        }
        g_present.blitInFlight[b] = true;
        result = g_vk.queuePresent(g_vk.presentQueue, &present);
    }
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        g_present.recreate = true;
    }
    g_present.shownCount++;
}

void waitBlits() {
    for (int i = 0; i < kBlitCount; i++) {
        if (g_present.blitInFlight[i]) {
            g_vk.waitForFences(g_vk.device, 1, &g_present.blitFences[i], VK_TRUE, UINT64_MAX);
            g_vk.resetFences(g_vk.device, 1, &g_present.blitFences[i]);
            g_present.blitInFlight[i] = false;
        }
    }
}

void sleepUntil(double target) {
    double remaining = target - now();
    if (remaining <= 0.0) {
        return;
    }
    if (g_present.timer && remaining > 0.0005) {
        LARGE_INTEGER due;
        due.QuadPart = -static_cast<LONGLONG>((remaining - 0.0003) * 1e7);
        if (SetWaitableTimer(g_present.timer, &due, 0, nullptr, nullptr, FALSE)) {
            WaitForSingleObject(g_present.timer, 50);
        }
    }
    while (now() < target) {
        YieldProcessor();
    }
}

void presenterLoop() {
    for (;;) {
        Job job;
        {
            std::unique_lock<std::mutex> lock(g_present.mutex);
            g_present.cv.wait(lock, [] { return g_present.stop || !g_present.jobs.empty(); });
            if (g_present.jobs.empty()) {
                break;
            }
            job = g_present.jobs.front();
            g_present.jobs.pop_front();
        }
        PresentSlot& slot = g_present.slots[job.slot];
        if (job.interpolated) {
            // Show the generated frame as soon as it exists, the real one half a frame interval later.
            g_vk.waitForFences(g_vk.device, 1, &slot.fence, VK_TRUE, UINT64_MAX);
            double shown = now();
            showImage(slot.interp, slot.interpReady);
            g_present.generatedCount++;
            bool backlog;
            {
                std::lock_guard<std::mutex> lock(g_present.mutex);
                backlog = !g_present.jobs.empty();
            }
            if (!backlog) {
                sleepUntil(shown + g_present.frameInterval.load() * 0.5);
            }
        }
        showImage(slot.real, slot.realReady);
        waitBlits();
        {
            std::lock_guard<std::mutex> lock(g_present.mutex);
            slot.busy = false;
        }
        g_present.cv.notify_all();
    }
}

void releaseFrameGeneration() {
    if (g_present.fg) {
        NVSDK_NGX_VULKAN_ReleaseFeature(g_present.fg);
        g_present.fg = nullptr;
    }
    if (g_present.fgParams) {
        NVSDK_NGX_VULKAN_DestroyParameters(g_present.fgParams);
        g_present.fgParams = nullptr;
    }
}

bool ensureFrameGeneration() {
    if (g_present.fg) {
        return true;
    }
    if (g_present.fgFailed) {
        return false;
    }
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_AllocateParameters(&g_present.fgParams);
    if (NVSDK_NGX_FAILED(result)) {
        g_present.fgFailed = true;
        g_message = ngxError("frame generation parameters", result);
        return false;
    }
    NVSDK_NGX_DLSSG_Create_Params create{};
    create.Width = g_present.width;
    create.Height = g_present.height;
    create.NativeBackbufferFormat = VK_FORMAT_R8G8B8A8_UNORM;
    create.RenderWidth = g_frame.images[1].width;
    create.RenderHeight = g_frame.images[1].height;
    waitForPreviousSubmit();
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(g_vk.commandBuffer, 0);
    g_vk.beginCommandBuffer(g_vk.commandBuffer, &begin);
    result = NGX_VK_CREATE_DLSSG(g_vk.commandBuffer, 1, 1, &g_present.fg, g_present.fgParams, &create);
    g_vk.endCommandBuffer(g_vk.commandBuffer);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g_vk.commandBuffer;
    if (submitTo(g_vk.queue, submit, g_vk.fence) == VK_SUCCESS) {
        g_frame.fenceInFlight = true;
        waitForPreviousSubmit();
    }
    if (NVSDK_NGX_FAILED(result) || !g_present.fg) {
        g_present.fgFailed = true;
        g_message = ngxError("frame generation creation failed", result);
        releaseFrameGeneration();
        return false;
    }
    return true;
}

}  // namespace

IRISDLSS_API void irisdlss_presenter_destroy() {
    if (!g_vk.device) {
        return;
    }
    if (g_present.thread.joinable()) {
        {
            std::lock_guard<std::mutex> lock(g_present.mutex);
            g_present.stop = true;
        }
        g_present.cv.notify_all();
        g_present.thread.join();
    }
    g_vk.deviceWaitIdle(g_vk.device);
    releaseFrameGeneration();
    for (PresentSlot& slot : g_present.slots) {
        destroyLocalImage(slot.real);
        destroyLocalImage(slot.interp);
        if (slot.commandBuffer) g_vk.freeCommandBuffers(g_vk.device, g_vk.commandPool, 1, &slot.commandBuffer);
        if (slot.fence) g_vk.destroyFence(g_vk.device, slot.fence, nullptr);
        if (slot.realReady) g_vk.destroySemaphore(g_vk.device, slot.realReady, nullptr);
        if (slot.interpReady) g_vk.destroySemaphore(g_vk.device, slot.interpReady, nullptr);
        slot = PresentSlot{};
    }
    for (int i = 0; i < kBlitCount; i++) {
        if (g_present.blitFences[i]) g_vk.destroyFence(g_vk.device, g_present.blitFences[i], nullptr);
        if (g_present.acquired[i]) g_vk.destroySemaphore(g_vk.device, g_present.acquired[i], nullptr);
        g_present.blitFences[i] = VK_NULL_HANDLE;
        g_present.acquired[i] = VK_NULL_HANDLE;
        g_present.blitInFlight[i] = false;
    }
    if (g_present.blitPool) {
        g_vk.destroyCommandPool(g_vk.device, g_present.blitPool, nullptr);
        g_present.blitPool = VK_NULL_HANDLE;
    }
    for (VkSemaphore semaphore : g_present.blitDone) {
        g_vk.destroySemaphore(g_vk.device, semaphore, nullptr);
    }
    g_present.blitDone.clear();
    g_present.swapImages.clear();
    if (g_present.swapchain) {
        g_vk.destroySwapchain(g_vk.device, g_present.swapchain, nullptr);
        g_present.swapchain = VK_NULL_HANDLE;
    }
    if (g_present.surface) {
        g_vk.destroySurface(g_vk.instance, g_present.surface, nullptr);
        g_present.surface = VK_NULL_HANDLE;
    }
    if (g_present.frame.view) g_vk.destroyImageView(g_vk.device, g_present.frame.view, nullptr);
    if (g_present.frame.image) g_vk.destroyImage(g_vk.device, g_present.frame.image, nullptr);
    if (g_present.frame.memory) g_vk.freeMemory(g_vk.device, g_present.frame.memory, nullptr);
    g_present.frame = SharedImage{};
    if (g_present.ready) g_vk.destroySemaphore(g_vk.device, g_present.ready, nullptr);
    if (g_present.consumed) g_vk.destroySemaphore(g_vk.device, g_present.consumed, nullptr);
    g_present.ready = g_present.consumed = VK_NULL_HANDLE;
    if (g_present.timer) {
        CloseHandle(g_present.timer);
        g_present.timer = nullptr;
    }
    if (g_present.window) {
        DestroyWindow(g_present.window);
        g_present.window = nullptr;
    }
    g_present.jobs.clear();
    g_present.stop = false;
    g_present.nextSlot = 0;
    g_present.nextBlit = 0;
    g_present.threadFailed = false;
    g_present.threadError.clear();
}

IRISDLSS_API int32_t irisdlss_presenter_create(uint64_t parentHwnd, uint32_t width, uint32_t height,
    int32_t vsync, int32_t externalSync, IrisDlssPresentResources* out) {
    if (!g_vk.ngxInitialized) {
        return fail(IRISDLSS_NGX_INIT_FAILED, "not initialized");
    }
    irisdlss_presenter_destroy();
    std::memset(out, 0, sizeof(*out));
    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);
    g_present.frequency = static_cast<double>(frequency.QuadPart);
    g_present.width = width;
    g_present.height = height;
    g_present.vsync = vsync != 0;
    g_present.externalSync = externalSync != 0;
    g_present.fgFailed = false;
    g_present.lastFrame = 0.0;
    g_present.statsStart = now();

    HWND parent = reinterpret_cast<HWND>(parentHwnd);
    g_present.window = createPresentWindow(parent, width, height);
    if (!g_present.window) {
        return fail(IRISDLSS_DEVICE_FAILED, "child window creation failed");
    }
    VkWin32SurfaceCreateInfoKHR surfaceInfo{VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR};
    surfaceInfo.hinstance = GetModuleHandleW(nullptr);
    surfaceInfo.hwnd = g_present.window;
    if (g_vk.createWin32Surface(g_vk.instance, &surfaceInfo, nullptr, &g_present.surface) != VK_SUCCESS) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, "vkCreateWin32SurfaceKHR failed");
    }
    VkBool32 supported = VK_FALSE;
    g_vk.getSurfaceSupport(g_vk.physicalDevice, g_vk.queueFamily, g_present.surface, &supported);
    if (!supported) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, "queue family cannot present to the window");
    }

    if (!createSharedImage(g_present.frame, VK_FORMAT_R8G8B8A8_UNORM, width, height, out->frame)) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, "frame image creation failed");
    }
    if (g_present.externalSync && (!createSharedSemaphore(g_present.ready, out->readySemaphore)
            || !createSharedSemaphore(g_present.consumed, out->consumedSemaphore))) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, "present semaphore creation failed");
    }
    for (PresentSlot& slot : g_present.slots) {
        VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        alloc.commandPool = g_vk.commandPool;
        alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        alloc.commandBufferCount = 1;
        if (!createLocalImage(slot.real, width, height) || !createLocalImage(slot.interp, width, height)
            || g_vk.allocateCommandBuffers(g_vk.device, &alloc, &slot.commandBuffer) != VK_SUCCESS) {
            irisdlss_presenter_destroy();
            return fail(IRISDLSS_DEVICE_FAILED, "present slot creation failed");
        }
        slot.fence = createFence();
        slot.realReady = createSemaphore();
        slot.interpReady = createSemaphore();
    }
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = g_vk.queueFamily;
    VkCommandBufferAllocateInfo blitAlloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    blitAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    blitAlloc.commandBufferCount = kBlitCount;
    if (g_vk.createCommandPool(g_vk.device, &poolInfo, nullptr, &g_present.blitPool) != VK_SUCCESS
        || (blitAlloc.commandPool = g_present.blitPool,
            g_vk.allocateCommandBuffers(g_vk.device, &blitAlloc, g_present.blitBuffers) != VK_SUCCESS)) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, "presenter command pool failed");
    }
    for (int i = 0; i < kBlitCount; i++) {
        g_present.blitFences[i] = createFence();
        g_present.acquired[i] = createSemaphore();
    }
    g_present.timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
        TIMER_ALL_ACCESS);

    // The frame image lives in GENERAL and belongs to OpenGL between frames.
    waitForPreviousSubmit();
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(g_vk.commandBuffer, 0);
    g_vk.beginCommandBuffer(g_vk.commandBuffer, &begin);
    VkImageMemoryBarrier toGeneral = barrier(g_present.frame.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    g_vk.cmdPipelineBarrier(g_vk.commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &toGeneral);
    g_vk.endCommandBuffer(g_vk.commandBuffer);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g_vk.commandBuffer;
    if (submitTo(g_vk.queue, submit, g_vk.fence) != VK_SUCCESS) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, "frame image setup failed");
    }
    g_frame.fenceInFlight = true;
    waitForPreviousSubmit();

    std::string error;
    if (!createSwapchain(error) && !error.empty()) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, error);
    }
    g_present.thread = std::thread(presenterLoop);
    g_message = "Vulkan presenter " + std::to_string(width) + "x" + std::to_string(height)
        + (g_vk.presentQueue != g_vk.queue ? " (own queue)" : " (shared queue)");
    return IRISDLSS_OK;
}

IRISDLSS_API void irisdlss_presenter_set_vsync(int32_t vsync) {
    bool value = vsync != 0;
    if (g_present.vsync.exchange(value) != value) {
        g_present.recreate = true;
    }
}

IRISDLSS_API int32_t irisdlss_present(const float* fg) {
    if (!g_present.thread.joinable()) {
        return fail(IRISDLSS_DEVICE_FAILED, "presenter not running");
    }
    if (g_present.threadFailed) {
        std::lock_guard<std::mutex> lock(g_present.mutex);
        return fail(IRISDLSS_DEVICE_FAILED, g_present.threadError);
    }
    double t = now();
    if (g_present.lastFrame > 0.0) {
        double dt = t - g_present.lastFrame;
        dt = dt < 1.0 / 1000.0 ? 1.0 / 1000.0 : dt > 0.1 ? 0.1 : dt;
        g_present.frameInterval = g_present.frameInterval.load() * 0.9 + dt * 0.1;
    }
    g_present.lastFrame = t;
    if (t - g_present.statsStart >= 1.0) {
        g_present.fps = static_cast<float>(g_present.shownCount.exchange(0) / (t - g_present.statsStart));
        g_present.fgRecently = g_present.generatedCount.exchange(0) > 0;
        g_present.statsStart = t;
    }

    int slotIndex = g_present.nextSlot;
    PresentSlot& slot = g_present.slots[slotIndex];
    {
        std::unique_lock<std::mutex> lock(g_present.mutex);
        g_present.cv.wait(lock, [&] { return !slot.busy; });
    }
    if (slot.fenceInFlight) {
        g_vk.waitForFences(g_vk.device, 1, &slot.fence, VK_TRUE, UINT64_MAX);
        g_vk.resetFences(g_vk.device, 1, &slot.fence);
        slot.fenceInFlight = false;
    }

    // Frame generation needs this frame's depth / motion vectors / hudless output at matching sizes.
    bool useFg = fg != nullptr && g_frame.dlss != nullptr && g_frame.images[3].width == g_present.width
        && g_frame.images[3].height == g_present.height && ensureFrameGeneration();

    uint32_t external = g_present.externalSync ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED;
    uint32_t local = g_present.externalSync ? g_vk.queueFamily : VK_QUEUE_FAMILY_IGNORED;
    VkCommandBuffer cmd = slot.commandBuffer;
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(cmd, 0);
    g_vk.beginCommandBuffer(cmd, &begin);

    VkImageMemoryBarrier copyIn[2] = {
        barrier(g_present.frame.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, external, local),
        barrier(slot.real.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
    };
    g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr,
        0, nullptr, 2, copyIn);
    VkImageCopy copy{};
    copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.dstSubresource = copy.srcSubresource;
    copy.extent = {g_present.width, g_present.height, 1};
    g_vk.cmdCopyImage(cmd, g_present.frame.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, slot.real.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

    NVSDK_NGX_Result fgResult = NVSDK_NGX_Result_Success;
    if (useFg) {
        VkImageMemoryBarrier toEval[5] = {
            barrier(slot.real.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
            barrier(slot.interp.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL),
            barrier(g_frame.images[1], VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, external, local),
            barrier(g_frame.images[2], VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, external, local),
            barrier(g_frame.images[3], VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, external, local),
        };
        g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0,
            nullptr, 0, nullptr, 5, toEval);

        auto view = [](VkImageView v, VkImage image, uint32_t w, uint32_t h, VkFormat format, bool readWrite) {
            return NVSDK_NGX_Create_ImageView_Resource_VK(v, image, kColorRange, format, w, h, readWrite);
        };
        NVSDK_NGX_Resource_VK backbuffer = view(slot.real.view, slot.real.image, g_present.width, g_present.height,
            VK_FORMAT_R8G8B8A8_UNORM, false);
        NVSDK_NGX_Resource_VK interp = view(slot.interp.view, slot.interp.image, g_present.width, g_present.height,
            VK_FORMAT_R8G8B8A8_UNORM, true);
        const SharedImage& d = g_frame.images[1];
        const SharedImage& m = g_frame.images[2];
        const SharedImage& h = g_frame.images[3];
        NVSDK_NGX_Resource_VK depth = view(d.view, d.image, d.width, d.height, d.format, false);
        NVSDK_NGX_Resource_VK mvecs = view(m.view, m.image, m.width, m.height, m.format, false);
        NVSDK_NGX_Resource_VK hudless = view(h.view, h.image, h.width, h.height, h.format, false);

        NVSDK_NGX_VK_DLSSG_Eval_Params eval{};
        eval.pBackbuffer = &backbuffer;
        eval.pDepth = &depth;
        eval.pMVecs = &mvecs;
        eval.pHudless = &hudless;
        eval.pOutputInterpFrame = &interp;
        NVSDK_NGX_DLSSG_Opt_Eval_Params opt{};
        std::memcpy(opt.cameraViewToClip, fg + IRISDLSS_FG_VIEW_TO_CLIP, sizeof(opt.cameraViewToClip));
        std::memcpy(opt.clipToCameraView, fg + IRISDLSS_FG_CLIP_TO_VIEW, sizeof(opt.clipToCameraView));
        std::memcpy(opt.clipToPrevClip, fg + IRISDLSS_FG_CLIP_TO_PREV_CLIP, sizeof(opt.clipToPrevClip));
        std::memcpy(opt.prevClipToClip, fg + IRISDLSS_FG_PREV_CLIP_TO_CLIP, sizeof(opt.prevClipToClip));
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                opt.clipToLensClip[i][j] = i == j ? 1.0f : 0.0f;
            }
        }
        std::memcpy(opt.jitterOffset, fg + IRISDLSS_FG_JITTER, sizeof(opt.jitterOffset));
        std::memcpy(opt.mvecScale, fg + IRISDLSS_FG_MVEC_SCALE, sizeof(opt.mvecScale));
        std::memcpy(opt.cameraPos, fg + IRISDLSS_FG_CAMERA_POS, sizeof(opt.cameraPos));
        std::memcpy(opt.cameraUp, fg + IRISDLSS_FG_CAMERA_UP, sizeof(opt.cameraUp));
        std::memcpy(opt.cameraRight, fg + IRISDLSS_FG_CAMERA_RIGHT, sizeof(opt.cameraRight));
        std::memcpy(opt.cameraFwd, fg + IRISDLSS_FG_CAMERA_FWD, sizeof(opt.cameraFwd));
        opt.cameraNear = fg[IRISDLSS_FG_NEAR];
        opt.cameraFar = fg[IRISDLSS_FG_FAR];
        opt.cameraFOV = fg[IRISDLSS_FG_FOV];
        opt.cameraAspectRatio = fg[IRISDLSS_FG_ASPECT];
        opt.reset = fg[IRISDLSS_FG_RESET] != 0.0f;
        opt.depthInverted = fg[IRISDLSS_FG_DEPTH_INVERTED] != 0.0f;
        opt.cameraMotionIncluded = true;
        opt.colorBuffersHDR = false;
        opt.motionVectorsInvalidValue = 1e30f;  // every vector we write is valid
        fgResult = NGX_VK_EVALUATE_DLSSG(cmd, g_present.fg, g_present.fgParams, &eval, &opt);

        VkImageMemoryBarrier afterEval[5] = {
            barrier(slot.real.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
            barrier(slot.interp.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
            barrier(g_frame.images[1], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, local, external),
            barrier(g_frame.images[2], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, local, external),
            barrier(g_frame.images[3], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, local, external),
        };
        g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0,
            nullptr, 0, nullptr, 5, afterEval);
    } else {
        VkImageMemoryBarrier toSource = barrier(slot.real.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr,
            0, nullptr, 1, &toSource);
    }
    VkImageMemoryBarrier release = barrier(g_present.frame.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_IMAGE_LAYOUT_GENERAL, local, external);
    g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
        nullptr, 0, nullptr, 1, &release);
    g_vk.endCommandBuffer(cmd);

    VkSemaphore signals[3];
    uint32_t signalCount = 0;
    signals[signalCount++] = slot.realReady;
    if (useFg) signals[signalCount++] = slot.interpReady;
    if (g_present.externalSync) signals[signalCount++] = g_present.consumed;
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = g_present.externalSync ? 1 : 0;
    submit.pWaitSemaphores = &g_present.ready;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = signalCount;
    submit.pSignalSemaphores = signals;
    if (submitTo(g_vk.queue, submit, slot.fence) != VK_SUCCESS) {
        return fail(IRISDLSS_DEVICE_FAILED, "present submit failed");
    }
    slot.fenceInFlight = true;
    {
        std::lock_guard<std::mutex> lock(g_present.mutex);
        slot.busy = true;
        g_present.jobs.push_back({slotIndex, useFg});
    }
    g_present.cv.notify_all();
    g_present.nextSlot = (slotIndex + 1) % kSlotCount;

    if (NVSDK_NGX_FAILED(fgResult)) {
        // Keep presenting; frame generation stays off until the presenter is recreated.
        g_present.fgFailed = true;
        g_message = ngxError("frame generation evaluate failed", fgResult);
        return IRISDLSS_DLSS_UNAVAILABLE;
    }
    return IRISDLSS_OK;
}

IRISDLSS_API void irisdlss_presenter_stats(float stats[3]) {
    stats[0] = g_present.fps;
    stats[1] = g_present.fgRecently ? 1.0f : 0.0f;
    stats[2] = g_present.threadFailed ? 1.0f : 0.0f;
}
