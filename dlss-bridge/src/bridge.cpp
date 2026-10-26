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
#include <chrono>
#include <cmath>
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
// Frames the game keeps in flight (RenderPearl waits for submit N-2), plus one of margin.
constexpr uint64_t kRetireFrames = 3;

std::string g_message;
std::wstring g_dataPath;

struct Vk {
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    PFN_vkGetDeviceProcAddr gdpa = nullptr;

    // Instance-level functions.
    PFN_vkGetPhysicalDeviceProperties2 getPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getMemoryProperties = nullptr;
    PFN_vkCreateWin32SurfaceKHR createWin32Surface = nullptr;
    PFN_vkDestroySurfaceKHR destroySurface = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR getSurfaceSupport = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR getSurfaceCapabilities = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR getSurfaceFormats = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR getSurfacePresentModes = nullptr;

    // Device-level functions.
    PFN_vkGetDeviceQueue getDeviceQueue = nullptr;
    PFN_vkDeviceWaitIdle deviceWaitIdle = nullptr;
    PFN_vkQueueWaitIdle queueWaitIdle = nullptr;
    PFN_vkCreateImage createImage = nullptr;
    PFN_vkDestroyImage destroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements getImageMemoryRequirements = nullptr;
    PFN_vkAllocateMemory allocateMemory = nullptr;
    PFN_vkFreeMemory freeMemory = nullptr;
    PFN_vkBindImageMemory bindImageMemory = nullptr;
    PFN_vkCreateImageView createImageView = nullptr;
    PFN_vkDestroyImageView destroyImageView = nullptr;
    PFN_vkCreateSemaphore createSemaphore = nullptr;
    PFN_vkDestroySemaphore destroySemaphore = nullptr;
    PFN_vkWaitSemaphores waitSemaphores = nullptr;
    PFN_vkCreateCommandPool createCommandPool = nullptr;
    PFN_vkDestroyCommandPool destroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers allocateCommandBuffers = nullptr;
    PFN_vkResetCommandBuffer resetCommandBuffer = nullptr;
    PFN_vkBeginCommandBuffer beginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer endCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier cmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyImage cmdCopyImage = nullptr;
    PFN_vkCmdBlitImage cmdBlitImage = nullptr;
    PFN_vkQueueSubmit queueSubmit = nullptr;
    PFN_vkCreateFence createFence = nullptr;
    PFN_vkDestroyFence destroyFence = nullptr;
    PFN_vkWaitForFences waitForFences = nullptr;
    PFN_vkResetFences resetFences = nullptr;
    PFN_vkCreateSwapchainKHR createSwapchain = nullptr;
    PFN_vkDestroySwapchainKHR destroySwapchain = nullptr;
    PFN_vkGetSwapchainImagesKHR getSwapchainImages = nullptr;
    PFN_vkAcquireNextImageKHR acquireNextImage = nullptr;
    PFN_vkQueuePresentKHR queuePresent = nullptr;
    // Debug capture.
    PFN_vkCreateBuffer createBuffer = nullptr;
    PFN_vkDestroyBuffer destroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements getBufferMemoryRequirements = nullptr;
    PFN_vkBindBufferMemory bindBufferMemory = nullptr;
    PFN_vkMapMemory mapMemory = nullptr;
    PFN_vkCmdCopyImageToBuffer cmdCopyImageToBuffer = nullptr;
    PFN_vkWaitForPresentKHR waitForPresent = nullptr;

    // The game's objects (never destroyed here).
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkQueue queue = VK_NULL_HANDLE;         // the game's graphics queue (index 0 of the family)
    int32_t presentQueueIndex = -1;         // our extra queue in that family, -1 if there was none
    VkQueue presentQueue = VK_NULL_HANDLE;  // used only by the presenter thread
    uint32_t opticalFlowFamily = UINT32_MAX;
    std::string deviceName;
    std::string fgExtensionsMissing;
    bool presentWait = false;  // VK_KHR_present_id + VK_KHR_present_wait enabled

    bool ngxInitialized = false;
    NVSDK_NGX_Parameter* capabilities = nullptr;
};

struct Dlss {
    NVSDK_NGX_Handle* handle = nullptr;
    NVSDK_NGX_Parameter* params = nullptr;
    uint32_t renderWidth = 0;
    uint32_t renderHeight = 0;
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
};

// NGX features released once no frame in flight can still use them.
struct Retired {
    NVSDK_NGX_Handle* handle;
    NVSDK_NGX_Parameter* params;
    uint64_t frame;
};

Vk g_vk;
Dlss g_dlss;
std::vector<Retired> g_retired;
uint64_t g_frameCounter = 0;
int32_t g_fgMax = -1;  // irisdlss_frame_generation_max, read once after NGX init

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

void addUnique(std::vector<const char*>& list, const char* name) {
    for (const char* existing : list) {
        if (std::strcmp(existing, name) == 0) {
            return;
        }
    }
    list.push_back(name);
}

template <typename T>
void loadInstance(T& fn, const char* name) {
    fn = reinterpret_cast<T>(g_vk.gipa(g_vk.instance, name));
}

template <typename T>
void loadDevice(T& fn, const char* name) {
    fn = reinterpret_cast<T>(g_vk.gdpa(g_vk.device, name));
}

// DLSS-G's discovery info (its extension requirements are queried with it).
NVSDK_NGX_FeatureDiscoveryInfo frameGenerationDiscovery() {
    static const wchar_t* paths[1];
    static NVSDK_NGX_FeatureCommonInfo common{};
    paths[0] = g_dataPath.c_str();
    common.PathListInfo.Path = paths;
    common.PathListInfo.Length = 1;
    NVSDK_NGX_FeatureDiscoveryInfo discovery{};
    discovery.SDKVersion = NVSDK_NGX_Version_API;
    discovery.FeatureID = NVSDK_NGX_Feature_FrameGeneration;
    discovery.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    discovery.Identifier.v.ProjectDesc.ProjectId = kProjectId;
    discovery.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    discovery.Identifier.v.ProjectDesc.EngineVersion = kEngineVersion;
    discovery.ApplicationDataPath = g_dataPath.c_str();
    discovery.FeatureInfo = &common;
    return discovery;
}

void retire(NVSDK_NGX_Handle* handle, NVSDK_NGX_Parameter* params) {
    if (handle || params) {
        g_retired.push_back({handle, params, g_frameCounter});
    }
}

void releaseRetired(bool all) {
    for (size_t i = 0; i < g_retired.size();) {
        if (all || g_frameCounter - g_retired[i].frame >= kRetireFrames) {
            if (g_retired[i].handle) NVSDK_NGX_VULKAN_ReleaseFeature(g_retired[i].handle);
            if (g_retired[i].params) NVSDK_NGX_VULKAN_DestroyParameters(g_retired[i].params);
            g_retired.erase(g_retired.begin() + static_cast<std::ptrdiff_t>(i));
        } else {
            i++;
        }
    }
}

bool isDepthFormat(VkFormat format) {
    return format == VK_FORMAT_D16_UNORM || format == VK_FORMAT_X8_D24_UNORM_PACK32 || format == VK_FORMAT_D32_SFLOAT
        || format == VK_FORMAT_D16_UNORM_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT
        || format == VK_FORMAT_D32_SFLOAT_S8_UINT;
}

bool hasStencil(VkFormat format) {
    return format == VK_FORMAT_D16_UNORM_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT
        || format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_S8_UINT;
}

// Subresource of the image a view / NGX resource covers (depth only for depth formats).
VkImageSubresourceRange viewRange(VkFormat format) {
    return {isDepthFormat(format) ? VkImageAspectFlags(VK_IMAGE_ASPECT_DEPTH_BIT) : VkImageAspectFlags(VK_IMAGE_ASPECT_COLOR_BIT),
        0, 1, 0, 1};
}

VkImageMemoryBarrier barrier(VkImage image, VkFormat format, VkImageLayout from, VkImageLayout to) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    b.dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange = viewRange(format);
    if (hasStencil(format)) {
        // Barriers on a combined depth/stencil image cover both aspects.
        b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
    }
    return b;
}

VkImageMemoryBarrier barrier(const IrisDlssImage& image, VkImageLayout from, VkImageLayout to) {
    return barrier(reinterpret_cast<VkImage>(image.image), static_cast<VkFormat>(image.format), from, to);
}

void pipelineBarrier(VkCommandBuffer cmd, uint32_t count, const VkImageMemoryBarrier* barriers) {
    g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr,
        0, nullptr, count, barriers);
}

NVSDK_NGX_Resource_VK resource(const IrisDlssImage& image, bool readWrite) {
    VkFormat format = static_cast<VkFormat>(image.format);
    return NVSDK_NGX_Create_ImageView_Resource_VK(reinterpret_cast<VkImageView>(image.view),
        reinterpret_cast<VkImage>(image.image), viewRange(format), format, image.width, image.height, readWrite);
}

}  // namespace

IRISDLSS_API const char* irisdlss_last_message() {
    return g_message.c_str();
}

IRISDLSS_API void irisdlss_set_data_path(const wchar_t* dataPath) {
    g_dataPath = dataPath ? dataPath : L"";
}

// ---- Device creation ----------------------------------------------------------------------------

IRISDLSS_API int32_t irisdlss_create_instance(uint64_t gipaAddress, const void* createInfo, const void* allocator,
    void* outInstance) {
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipaAddress);
    auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
    auto enumerate = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
        gipa(nullptr, "vkEnumerateInstanceExtensionProperties"));
    const auto* info = static_cast<const VkInstanceCreateInfo*>(createInfo);
    const auto* callbacks = static_cast<const VkAllocationCallbacks*>(allocator);
    auto* instance = static_cast<VkInstance*>(outInstance);

    uint32_t count = 0;
    enumerate(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    enumerate(nullptr, &count, available.data());

    std::vector<const char*> names(info->ppEnabledExtensionNames,
        info->ppEnabledExtensionNames + info->enabledExtensionCount);
    unsigned int ngxInstanceCount = 0, ngxDeviceCount = 0;
    const char** ngxInstanceExts = nullptr;
    const char** ngxDeviceExts = nullptr;
    NVSDK_NGX_VULKAN_RequiredExtensions(&ngxInstanceCount, &ngxInstanceExts, &ngxDeviceCount, &ngxDeviceExts);
    for (unsigned int i = 0; i < ngxInstanceCount; i++) {
        if (hasExtension(available, ngxInstanceExts[i])) {
            addUnique(names, ngxInstanceExts[i]);
        }
    }
    NVSDK_NGX_FeatureDiscoveryInfo discovery = frameGenerationDiscovery();
    uint32_t fgCount = 0;
    VkExtensionProperties* fgExts = nullptr;
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&discovery, &fgCount, &fgExts))) {
        for (uint32_t i = 0; i < fgCount; i++) {
            if (hasExtension(available, fgExts[i].extensionName)) {
                addUnique(names, fgExts[i].extensionName);
            }
        }
    }

    VkInstanceCreateInfo patched = *info;
    patched.enabledExtensionCount = static_cast<uint32_t>(names.size());
    patched.ppEnabledExtensionNames = names.data();
    VkResult result = createInstance(&patched, callbacks, instance);
    if (result != VK_SUCCESS && names.size() != info->enabledExtensionCount) {
        result = createInstance(info, callbacks, instance);
    }
    return result;
}

IRISDLSS_API int32_t irisdlss_create_device(uint64_t gipaAddress, uint64_t instanceHandle, uint64_t physicalHandle,
    const void* createInfo, const void* allocator, void* outDevice) {
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipaAddress);
    auto instance = reinterpret_cast<VkInstance>(instanceHandle);
    auto physical = reinterpret_cast<VkPhysicalDevice>(physicalHandle);
    const auto* info = static_cast<const VkDeviceCreateInfo*>(createInfo);
    const auto* callbacks = static_cast<const VkAllocationCallbacks*>(allocator);
    auto* device = static_cast<VkDevice*>(outDevice);
    auto createDevice = reinterpret_cast<PFN_vkCreateDevice>(gipa(instance, "vkCreateDevice"));
    auto queueFamilies = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
        gipa(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    auto enumerate = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
        gipa(instance, "vkEnumerateDeviceExtensionProperties"));
    auto features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(gipa(instance, "vkGetPhysicalDeviceFeatures2"));
    auto properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
        gipa(instance, "vkGetPhysicalDeviceProperties2"));

    VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    properties2(physical, &props);
    if (props.properties.vendorID != 0x10DE) {
        g_message = std::string("not an NVIDIA GPU: ") + props.properties.deviceName;
        return createDevice(physical, info, callbacks, device);
    }

    // Extensions: the game's, NGX's and DLSS-G's (those the GPU has).
    uint32_t count = 0;
    enumerate(physical, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    enumerate(physical, nullptr, &count, available.data());
    std::vector<const char*> names(info->ppEnabledExtensionNames,
        info->ppEnabledExtensionNames + info->enabledExtensionCount);
    unsigned int ngxInstanceCount = 0, ngxDeviceCount = 0;
    const char** ngxInstanceExts = nullptr;
    const char** ngxDeviceExts = nullptr;
    NVSDK_NGX_VULKAN_RequiredExtensions(&ngxInstanceCount, &ngxInstanceExts, &ngxDeviceCount, &ngxDeviceExts);
    for (unsigned int i = 0; i < ngxDeviceCount; i++) {
        if (hasExtension(available, ngxDeviceExts[i])) {
            addUnique(names, ngxDeviceExts[i]);
        }
    }
    NVSDK_NGX_FeatureDiscoveryInfo discovery = frameGenerationDiscovery();
    uint32_t fgCount = 0;
    VkExtensionProperties* fgExts = nullptr;
    std::vector<std::string> fgNames;
    g_vk.fgExtensionsMissing.clear();
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(instance, physical, &discovery,
            &fgCount, &fgExts))) {
        for (uint32_t i = 0; i < fgCount; i++) {
            if (hasExtension(available, fgExts[i].extensionName)) {
                fgNames.emplace_back(fgExts[i].extensionName);
            } else {
                g_vk.fgExtensionsMissing += std::string(fgExts[i].extensionName) + " ";
            }
        }
    }
    for (const std::string& name : fgNames) {
        addUnique(names, name.c_str());
    }
    bool opticalFlowExt = hasExtension(available, VK_NV_OPTICAL_FLOW_EXTENSION_NAME);
    if (opticalFlowExt) {
        addUnique(names, VK_NV_OPTICAL_FLOW_EXTENSION_NAME);
    }
    // The presenter paces frames on when they actually reach the screen (VK_KHR_present_wait).
    bool presentWaitExt = hasExtension(available, VK_KHR_PRESENT_ID_EXTENSION_NAME)
        && hasExtension(available, VK_KHR_PRESENT_WAIT_EXTENSION_NAME);

    // Features: buffer device address (NGX) and optical flow (DLSS-G), set in the game's own feature
    // structs where it has them, otherwise in structs placed in front of its chain.
    VkPhysicalDeviceOpticalFlowFeaturesNV opticalFlowSupported{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV};
    VkPhysicalDeviceVulkan12Features vk12Supported{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDevicePresentIdFeaturesKHR presentIdSupported{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR};
    VkPhysicalDevicePresentWaitFeaturesKHR presentWaitSupported{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR};
    presentIdSupported.pNext = &presentWaitSupported;
    presentWaitSupported.pNext = opticalFlowExt ? &opticalFlowSupported : nullptr;
    vk12Supported.pNext = presentWaitExt ? static_cast<void*>(&presentIdSupported)
        : opticalFlowExt ? static_cast<void*>(&opticalFlowSupported) : nullptr;
    VkPhysicalDeviceFeatures2 supported{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    supported.pNext = &vk12Supported;
    features2(physical, &supported);

    VkPhysicalDeviceVulkan12Features* gameVk12 = nullptr;
    VkPhysicalDeviceBufferDeviceAddressFeatures* gameBda = nullptr;
    VkPhysicalDeviceOpticalFlowFeaturesNV* gameOpticalFlow = nullptr;
    for (auto* s = static_cast<VkBaseOutStructure*>(const_cast<void*>(info->pNext)); s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES) {
            gameVk12 = reinterpret_cast<VkPhysicalDeviceVulkan12Features*>(s);
        } else if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES) {
            gameBda = reinterpret_cast<VkPhysicalDeviceBufferDeviceAddressFeatures*>(s);
        } else if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV) {
            gameOpticalFlow = reinterpret_cast<VkPhysicalDeviceOpticalFlowFeaturesNV*>(s);
        }
    }
    const void* chain = info->pNext;
    VkPhysicalDeviceBufferDeviceAddressFeatures extraBda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};
    VkPhysicalDeviceOpticalFlowFeaturesNV extraOpticalFlow{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV};
    if (vk12Supported.bufferDeviceAddress) {
        if (gameVk12) {
            gameVk12->bufferDeviceAddress = VK_TRUE;
        } else if (gameBda) {
            gameBda->bufferDeviceAddress = VK_TRUE;
        } else {
            extraBda.bufferDeviceAddress = VK_TRUE;
            extraBda.pNext = const_cast<void*>(chain);
            chain = &extraBda;
        }
    }
    VkPhysicalDevicePresentIdFeaturesKHR extraPresentId{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR};
    VkPhysicalDevicePresentWaitFeaturesKHR extraPresentWait{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR};
    bool presentWait = presentWaitExt && presentIdSupported.presentId && presentWaitSupported.presentWait;
    if (presentWait) {
        addUnique(names, VK_KHR_PRESENT_ID_EXTENSION_NAME);
        addUnique(names, VK_KHR_PRESENT_WAIT_EXTENSION_NAME);
        extraPresentId.presentId = VK_TRUE;
        extraPresentWait.presentWait = VK_TRUE;
        extraPresentWait.pNext = const_cast<void*>(chain);
        extraPresentId.pNext = &extraPresentWait;
        chain = &extraPresentId;
    }
    if (opticalFlowExt && opticalFlowSupported.opticalFlow) {
        if (gameOpticalFlow) {
            gameOpticalFlow->opticalFlow = VK_TRUE;
        } else {
            extraOpticalFlow.opticalFlow = VK_TRUE;
            extraOpticalFlow.pNext = const_cast<void*>(chain);
            chain = &extraOpticalFlow;
        }
    }

    // Queues: one more in the graphics family for the presenter thread, plus an optical-flow queue.
    uint32_t familyCount = 0;
    queueFamilies(physical, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    queueFamilies(physical, &familyCount, families.data());
    std::vector<VkDeviceQueueCreateInfo> queues(info->pQueueCreateInfos,
        info->pQueueCreateInfos + info->queueCreateInfoCount);
    std::vector<float> presentPriorities;
    static const float kOnePriority = 1.0f;
    int graphics = -1;
    for (size_t i = 0; i < queues.size(); i++) {
        VkQueueFlags flags = families[queues[i].queueFamilyIndex].queueFlags;
        if ((flags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            graphics = static_cast<int>(i);
            break;
        }
    }
    if (graphics < 0) {
        g_message = "the game's device has no graphics queue";
        return createDevice(physical, info, callbacks, device);
    }
    VkDeviceQueueCreateInfo& graphicsQueues = queues[graphics];
    const uint32_t graphicsFamily = graphicsQueues.queueFamilyIndex;
    int32_t presentIndex = -1;
    if (families[graphicsQueues.queueFamilyIndex].queueCount > graphicsQueues.queueCount) {
        presentPriorities.assign(graphicsQueues.pQueuePriorities,
            graphicsQueues.pQueuePriorities + graphicsQueues.queueCount);
        presentPriorities.push_back(graphicsQueues.queueCount > 0 ? graphicsQueues.pQueuePriorities[0] : 1.0f);
        presentIndex = static_cast<int32_t>(graphicsQueues.queueCount);
        graphicsQueues.queueCount++;
        graphicsQueues.pQueuePriorities = presentPriorities.data();
    }
    uint32_t opticalFlowFamily = UINT32_MAX;
    for (uint32_t i = 0; i < familyCount; i++) {
        if (i != graphicsFamily && (families[i].queueFlags & VK_QUEUE_OPTICAL_FLOW_BIT_NV)) {
            opticalFlowFamily = i;
            break;
        }
    }
    if (opticalFlowFamily != UINT32_MAX) {
        bool listed = false;
        for (const auto& q : queues) {
            listed |= q.queueFamilyIndex == opticalFlowFamily;
        }
        if (!listed) {
            VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
            q.queueFamilyIndex = opticalFlowFamily;
            q.queueCount = 1;
            q.pQueuePriorities = &kOnePriority;
            queues.push_back(q);  // graphicsQueues is not used past this point
        }
    }

    VkDeviceCreateInfo patched = *info;
    patched.pNext = chain;
    patched.queueCreateInfoCount = static_cast<uint32_t>(queues.size());
    patched.pQueueCreateInfos = queues.data();
    patched.enabledExtensionCount = static_cast<uint32_t>(names.size());
    patched.ppEnabledExtensionNames = names.data();
    VkResult result = createDevice(physical, &patched, callbacks, device);
    if (result != VK_SUCCESS) {
        g_message = "vkCreateDevice with the DLSS extensions failed (" + std::to_string(result) + ")";
        return createDevice(physical, info, callbacks, device);
    }

    g_vk.gipa = gipa;
    g_vk.instance = instance;
    g_vk.physicalDevice = physical;
    g_vk.device = *device;
    g_vk.queueFamily = graphicsFamily;
    g_vk.presentQueueIndex = presentIndex;
    g_vk.opticalFlowFamily = opticalFlowFamily;
    g_vk.presentWait = presentWait;
    g_vk.deviceName = props.properties.deviceName;
    g_message = "DLSS extensions enabled on " + g_vk.deviceName;
    return result;
}

IRISDLSS_API int32_t irisdlss_init() {
    if (g_vk.ngxInitialized) {
        return IRISDLSS_OK;
    }
    if (!g_vk.device) {
        return fail(IRISDLSS_DEVICE_FAILED, g_message.empty()
            ? "the game's Vulkan device was not created through the bridge (Graphics API not Vulkan?)" : g_message);
    }
    loadInstance(g_vk.gdpa, "vkGetDeviceProcAddr");
    loadInstance(g_vk.getPhysicalDeviceProperties2, "vkGetPhysicalDeviceProperties2");
    loadInstance(g_vk.getMemoryProperties, "vkGetPhysicalDeviceMemoryProperties");
    loadInstance(g_vk.createWin32Surface, "vkCreateWin32SurfaceKHR");
    loadInstance(g_vk.destroySurface, "vkDestroySurfaceKHR");
    loadInstance(g_vk.getSurfaceSupport, "vkGetPhysicalDeviceSurfaceSupportKHR");
    loadInstance(g_vk.getSurfaceCapabilities, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    loadInstance(g_vk.getSurfaceFormats, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    loadInstance(g_vk.getSurfacePresentModes, "vkGetPhysicalDeviceSurfacePresentModesKHR");
    loadDevice(g_vk.getDeviceQueue, "vkGetDeviceQueue");
    loadDevice(g_vk.deviceWaitIdle, "vkDeviceWaitIdle");
    loadDevice(g_vk.queueWaitIdle, "vkQueueWaitIdle");
    loadDevice(g_vk.createImage, "vkCreateImage");
    loadDevice(g_vk.destroyImage, "vkDestroyImage");
    loadDevice(g_vk.getImageMemoryRequirements, "vkGetImageMemoryRequirements");
    loadDevice(g_vk.allocateMemory, "vkAllocateMemory");
    loadDevice(g_vk.freeMemory, "vkFreeMemory");
    loadDevice(g_vk.bindImageMemory, "vkBindImageMemory");
    loadDevice(g_vk.createImageView, "vkCreateImageView");
    loadDevice(g_vk.destroyImageView, "vkDestroyImageView");
    loadDevice(g_vk.createSemaphore, "vkCreateSemaphore");
    loadDevice(g_vk.destroySemaphore, "vkDestroySemaphore");
    loadDevice(g_vk.waitSemaphores, "vkWaitSemaphores");
    loadDevice(g_vk.createCommandPool, "vkCreateCommandPool");
    loadDevice(g_vk.destroyCommandPool, "vkDestroyCommandPool");
    loadDevice(g_vk.allocateCommandBuffers, "vkAllocateCommandBuffers");
    loadDevice(g_vk.resetCommandBuffer, "vkResetCommandBuffer");
    loadDevice(g_vk.beginCommandBuffer, "vkBeginCommandBuffer");
    loadDevice(g_vk.endCommandBuffer, "vkEndCommandBuffer");
    loadDevice(g_vk.cmdPipelineBarrier, "vkCmdPipelineBarrier");
    loadDevice(g_vk.cmdCopyImage, "vkCmdCopyImage");
    loadDevice(g_vk.cmdBlitImage, "vkCmdBlitImage");
    loadDevice(g_vk.queueSubmit, "vkQueueSubmit");
    loadDevice(g_vk.createFence, "vkCreateFence");
    loadDevice(g_vk.destroyFence, "vkDestroyFence");
    loadDevice(g_vk.waitForFences, "vkWaitForFences");
    loadDevice(g_vk.resetFences, "vkResetFences");
    loadDevice(g_vk.createSwapchain, "vkCreateSwapchainKHR");
    loadDevice(g_vk.destroySwapchain, "vkDestroySwapchainKHR");
    loadDevice(g_vk.getSwapchainImages, "vkGetSwapchainImagesKHR");
    loadDevice(g_vk.acquireNextImage, "vkAcquireNextImageKHR");
    loadDevice(g_vk.queuePresent, "vkQueuePresentKHR");
    loadDevice(g_vk.createBuffer, "vkCreateBuffer");
    loadDevice(g_vk.destroyBuffer, "vkDestroyBuffer");
    loadDevice(g_vk.getBufferMemoryRequirements, "vkGetBufferMemoryRequirements");
    loadDevice(g_vk.bindBufferMemory, "vkBindBufferMemory");
    loadDevice(g_vk.mapMemory, "vkMapMemory");
    loadDevice(g_vk.cmdCopyImageToBuffer, "vkCmdCopyImageToBuffer");
    if (g_vk.presentWait) {
        loadDevice(g_vk.waitForPresent, "vkWaitForPresentKHR");
        g_vk.presentWait = g_vk.waitForPresent != nullptr;
    }
    g_vk.getDeviceQueue(g_vk.device, g_vk.queueFamily, 0, &g_vk.queue);
    if (g_vk.presentQueueIndex >= 0) {
        g_vk.getDeviceQueue(g_vk.device, g_vk.queueFamily, static_cast<uint32_t>(g_vk.presentQueueIndex),
            &g_vk.presentQueue);
    }

    // NGX looks for nvngx_dlss.dll next to the executable (java.exe) by default; add the data path.
    const wchar_t* searchPaths[] = {g_dataPath.c_str()};
    NVSDK_NGX_FeatureCommonInfo featureInfo{};
    featureInfo.PathListInfo.Path = searchPaths;
    featureInfo.PathListInfo.Length = 1;
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Init_with_ProjectID(kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM,
        kEngineVersion, g_dataPath.c_str(), g_vk.instance, g_vk.physicalDevice, g_vk.device, g_vk.gipa, g_vk.gdpa,
        &featureInfo);
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
    int available = 0;
    result = NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    if (NVSDK_NGX_FAILED(result) || !available) {
        int initResult = 0;
        NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initResult);
        return fail(IRISDLSS_DLSS_UNAVAILABLE,
            ngxError("DLSS Super Resolution unavailable", static_cast<NVSDK_NGX_Result>(initResult)));
    }
    g_fgMax = -1;
    int fgMax = irisdlss_frame_generation_max();
    g_message = "DLSS Super Resolution available on " + g_vk.deviceName + " (game's Vulkan device"
        + (g_vk.presentQueue ? ", presenter queue" : ", no presenter queue")
        + (fgMax > 0 ? ", Frame Generation up to " + std::to_string(fgMax) + "x)" : ", no Frame Generation)");
    return IRISDLSS_OK;
}

IRISDLSS_API int32_t irisdlss_frame_generation_max() {
    if (!g_vk.capabilities) {
        return 0;
    }
    if (g_fgMax >= 0) {
        return g_fgMax;
    }
    int available = 0;
    if (NVSDK_NGX_FAILED(NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_FrameGeneration_Available,
            &available)) || !available) {
        return 0;
    }
    // Generated frames per rendered one; unset or 1 means no multi frame generation.
    unsigned int generated = 1;
    if (NVSDK_NGX_FAILED(NVSDK_NGX_Parameter_GetUI(g_vk.capabilities, NVSDK_NGX_DLSSG_Parameter_MultiFrameCountMax,
            &generated)) || generated < 1) {
        generated = 1;
    }
    g_fgMax = generated >= 3 ? 4 : static_cast<int32_t>(generated) + 1;
    return g_fgMax;
}

IRISDLSS_API void irisdlss_frame_submitted() {
    g_frameCounter++;
    if (!g_retired.empty()) {
        releaseRetired(false);
    }
}

// ---- DLSS Super Resolution ----------------------------------------------------------------------

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

namespace {
void releaseFrameGeneration();
}

IRISDLSS_API void irisdlss_destroy_resources() {
    // Frame generation is created for the DLSS render size and reads its images.
    releaseFrameGeneration();
    retire(g_dlss.handle, g_dlss.params);
    g_dlss = Dlss{};
}

IRISDLSS_API int32_t irisdlss_create_resources(uint64_t commandBuffer, uint32_t renderWidth, uint32_t renderHeight,
    uint32_t outWidth, uint32_t outHeight, int32_t quality, int32_t depthInverted, int32_t preset) {
    if (!g_vk.ngxInitialized) {
        return fail(IRISDLSS_NGX_INIT_FAILED, "not initialized");
    }
    irisdlss_destroy_resources();
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_AllocateParameters(&g_dlss.params);
    if (NVSDK_NGX_FAILED(result)) {
        return fail(IRISDLSS_NGX_INIT_FAILED, ngxError("NGX parameter allocation failed", result));
    }
    // Model preset (NVSDK_NGX_DLSS_Hint_Render_Preset): 0 = driver default, 11 = K (transformer).
    for (const char* key : {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality}) {
        NVSDK_NGX_Parameter_SetUI(g_dlss.params, key, static_cast<unsigned int>(preset));
    }
    NVSDK_NGX_DLSS_Create_Params create{};
    create.Feature.InWidth = renderWidth;
    create.Feature.InHeight = renderHeight;
    create.Feature.InTargetWidth = outWidth;
    create.Feature.InTargetHeight = outHeight;
    create.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(quality);
    create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes
        | (depthInverted ? NVSDK_NGX_DLSS_Feature_Flags_DepthInverted : 0);
    result = NGX_VULKAN_CREATE_DLSS_EXT1(g_vk.device, reinterpret_cast<VkCommandBuffer>(commandBuffer), 1, 1,
        &g_dlss.handle, g_dlss.params, &create);
    if (NVSDK_NGX_FAILED(result)) {
        irisdlss_destroy_resources();
        return fail(IRISDLSS_DLSS_UNAVAILABLE, ngxError("DLSS feature creation failed", result));
    }
    g_dlss.renderWidth = renderWidth;
    g_dlss.renderHeight = renderHeight;
    g_dlss.outWidth = outWidth;
    g_dlss.outHeight = outHeight;
    g_message = "DLSS " + std::to_string(renderWidth) + "x" + std::to_string(renderHeight) + " -> "
        + std::to_string(outWidth) + "x" + std::to_string(outHeight);
    return IRISDLSS_OK;
}

IRISDLSS_API int32_t irisdlss_evaluate(uint64_t commandBuffer, const IrisDlssImage* color, const IrisDlssImage* depth,
    const IrisDlssImage* motion, const IrisDlssImage* output, float jitterX, float jitterY, float mvScaleX,
    float mvScaleY, int32_t reset) {
    if (!g_dlss.handle) {
        return fail(IRISDLSS_DLSS_UNAVAILABLE, "no DLSS feature");
    }
    auto cmd = reinterpret_cast<VkCommandBuffer>(commandBuffer);
    // Inputs read-only, output stays GENERAL for storage writes.
    VkImageMemoryBarrier before[4] = {
        barrier(*color, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
        barrier(*depth, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
        barrier(*motion, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
        barrier(*output, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL),
    };
    pipelineBarrier(cmd, 4, before);

    NVSDK_NGX_Resource_VK colorResource = resource(*color, false);
    NVSDK_NGX_Resource_VK depthResource = resource(*depth, false);
    NVSDK_NGX_Resource_VK motionResource = resource(*motion, false);
    NVSDK_NGX_Resource_VK outputResource = resource(*output, true);
    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
    eval.Feature.pInColor = &colorResource;
    eval.Feature.pInOutput = &outputResource;
    eval.pInDepth = &depthResource;
    eval.pInMotionVectors = &motionResource;
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InRenderSubrectDimensions = {color->width, color->height};
    eval.InReset = reset;
    eval.InMVScaleX = mvScaleX;
    eval.InMVScaleY = mvScaleY;
    NVSDK_NGX_Result result = NGX_VULKAN_EVALUATE_DLSS_EXT(cmd, g_dlss.handle, g_dlss.params, &eval);

    VkImageMemoryBarrier after[4] = {
        barrier(*color, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        barrier(*depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        barrier(*motion, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        barrier(*output, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL),
    };
    pipelineBarrier(cmd, 4, after);
    if (NVSDK_NGX_FAILED(result)) {
        return fail(IRISDLSS_DLSS_UNAVAILABLE, ngxError("DLSS evaluate failed", result));
    }
    return IRISDLSS_OK;
}

// ---- Presentation -------------------------------------------------------------------------------
// The finished frame leaves through a Vulkan swapchain on a child window that covers the game
// window; the game's own swapchain stays idle. The copy of the frame and DLSS-G run in the game's
// command buffer; the presenter thread waits for that submission (timeline semaphore) on its own
// queue and shows the generated frames first, evenly spaced, then the real one.

namespace {

constexpr int kSlotCount = 3;
constexpr int kMaxGenerated = 3;  // DLSS 4 multi frame generation: up to 4x
constexpr int kBlitCount = 4;
constexpr VkImageSubresourceRange kColorRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

struct LocalImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

struct PresentSlot {
    LocalImage real;
    LocalImage interp[kMaxGenerated];  // [1] and [2] are created when multi frame generation first needs them
    bool busy = false;  // queued for / shown by the presenter thread (guarded by Presenter::mutex)
};

struct Job {
    int slot;
    int generated;  // generated frames to show before the real one (0 = none)
    uint64_t value;  // timeline value signaled when the slot's images are rendered
    double queued;   // when the game handed the frame over (render thread)
    double started;  // when the game started the frame (irisdlss_presenter_frame_start), 0 if unknown
};

// Presentation timing, gathered on the presenter thread and published once a second.
struct Timing {
    double lastShow = 0.0;
    double windowStart = 0.0;
    double intervalSum = 0.0;
    double intervalSqSum = 0.0;
    double intervalMax = 0.0;
    uint32_t intervals = 0;
    double latencySum = 0.0;
    uint32_t latencies = 0;
};

struct Presenter {
    HWND window = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;

    VkSemaphore timeline = VK_NULL_HANDLE;
    uint64_t timelineValue = 0;
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
    std::atomic<int> generatedWanted{1};  // generated frames per rendered one (irisdlss_presenter_set_generated)
    int lastGeneratedCount = 0;           // of the last frame-generated present (render thread)

    // Pacing and stats.
    double frequency = 1.0;
    double lastFrame = 0.0;
    std::atomic<double> frameInterval{1.0 / 60.0};
    std::atomic<uint32_t> shownCount{0};
    std::atomic<uint32_t> generatedCount{0};
    double statsStart = 0.0;
    float fps = 0.0f;
    int fgFactor = 0;  // frame generation multiplier in the last second, 0 if it did not run
    Timing timing;
    std::atomic<float> intervalMs{0.0f};
    std::atomic<float> jitterMs{0.0f};
    std::atomic<float> worstMs{0.0f};
    std::atomic<float> latencyMs{0.0f};
    uint64_t presentId = 0;  // last VkPresentIdKHR (presenter thread)

    // Frame start pacing (low latency): when the presenter last took a job, how long a presenter
    // cycle and a game frame take.
    std::atomic<double> lastTake{0.0};
    std::atomic<int> lastGenerated{0};
    std::atomic<double> renderTime{0.0};  // frame start until the GPU finished it
    std::atomic<double> refreshPeriod{0.0};
    double refreshChecked = 0.0;
    // GPU completion watcher: when each frame's submission finished on the GPU.
    std::thread watcher;
    static constexpr int kStartedRing = 64;
    std::atomic<double> started[kStartedRing]{};
    double frameStart = 0.0;

    // Process exit without irisdlss_presenter_destroy: a joinable std::thread would terminate().
    ~Presenter() {
        if (thread.joinable()) {
            thread.detach();
        }
        if (watcher.joinable()) {
            watcher.detach();
        }
    }
};

Presenter g_present;

double now() {
    LARGE_INTEGER t;
    QueryPerformanceCounter(&t);
    return static_cast<double>(t.QuadPart) / g_present.frequency;
}

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

IrisDlssImage describe(const LocalImage& image, uint32_t width, uint32_t height) {
    return {reinterpret_cast<uint64_t>(image.image), reinterpret_cast<uint64_t>(image.view),
        VK_FORMAT_R8G8B8A8_UNORM, width, height};
}

VkSemaphore createSemaphore(bool timeline) {
    VkSemaphoreTypeCreateInfo type{VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO};
    type.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    info.pNext = timeline ? &type : nullptr;
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
        // UNORM, not SRGB: the frame is already display-encoded, exactly as the game shows it.
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

    g_vk.queueWaitIdle(g_vk.presentQueue);
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
        g_present.blitDone.push_back(createSemaphore(false));
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

// One more frame reached the screen (or the present queue, without present wait).
void markShown(double shown) {
    Timing& timing = g_present.timing;
    if (timing.lastShow > 0.0) {
        double interval = shown - timing.lastShow;
        timing.intervalSum += interval;
        timing.intervalSqSum += interval * interval;
        timing.intervalMax = interval > timing.intervalMax ? interval : timing.intervalMax;
        timing.intervals++;
    }
    timing.lastShow = shown;
}

// Blits source to the next swapchain image once the game's submission reached `ready`. Returns the
// present id to wait for (0 if nothing was presented or present wait is unavailable).
uint64_t showImage(const LocalImage& source, uint64_t ready) {
    if (g_present.recreate.exchange(false) || !g_present.swapchain) {
        std::string error;
        if (!createSwapchain(error)) {
            if (!error.empty()) {
                setThreadError(error);
            }
            g_present.recreate = true;
            return 0;
        }
    }
    int b = takeBlitSlot();
    uint32_t index = 0;
    VkResult result = g_vk.acquireNextImage(g_vk.device, g_present.swapchain, UINT64_MAX, g_present.acquired[b],
        VK_NULL_HANDLE, &index);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        g_present.recreate = true;
        return 0;
    }

    VkCommandBuffer cmd = g_present.blitBuffers[b];
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    g_vk.resetCommandBuffer(cmd, 0);
    g_vk.beginCommandBuffer(cmd, &begin);
    VkImage target = g_present.swapImages[index];
    VkImageMemoryBarrier toTransfer = barrier(target, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_UNDEFINED,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
        nullptr, 1, &toTransfer);
    // The game's images start at the bottom row (it flips when it presents itself): flip while copying.
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
    VkImageMemoryBarrier toPresent = barrier(target, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    g_vk.cmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
        nullptr, 0, nullptr, 1, &toPresent);
    g_vk.endCommandBuffer(cmd);

    VkSemaphore waits[2] = {g_present.acquired[b], g_present.timeline};
    uint64_t waitValues[2] = {0, ready};
    VkPipelineStageFlags stages[2] = {VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
    VkTimelineSemaphoreSubmitInfo timelineInfo{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
    timelineInfo.waitSemaphoreValueCount = 2;
    timelineInfo.pWaitSemaphoreValues = waitValues;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.pNext = &timelineInfo;
    submit.waitSemaphoreCount = 2;
    submit.pWaitSemaphores = waits;
    submit.pWaitDstStageMask = stages;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &g_present.blitDone[index];
    if (g_vk.queueSubmit(g_vk.presentQueue, 1, &submit, g_present.blitFences[b]) != VK_SUCCESS) {
        setThreadError("present blit submit failed");
        return 0;
    }
    g_present.blitInFlight[b] = true;
    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &g_present.blitDone[index];
    present.swapchainCount = 1;
    present.pSwapchains = &g_present.swapchain;
    present.pImageIndices = &index;
    uint64_t id = ++g_present.presentId;
    VkPresentIdKHR presentId{VK_STRUCTURE_TYPE_PRESENT_ID_KHR};
    presentId.swapchainCount = 1;
    presentId.pPresentIds = &id;
    present.pNext = g_vk.presentWait ? &presentId : nullptr;
    result = g_vk.queuePresent(g_vk.presentQueue, &present);
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        g_present.recreate = true;
    }
    g_present.shownCount++;
    if (!g_vk.presentWait) {
        markShown(now());
        return 0;
    }
    return id;
}

// Presenter thread: until the frame with this present id is on screen. False if it never got there
// in time (swapchain gone, presenter stopping).
bool waitDisplayed(uint64_t id) {
    if (id == 0 || !g_present.swapchain) {
        return false;
    }
    for (int attempt = 0; attempt < 10; attempt++) {
        VkResult result = g_vk.waitForPresent(g_vk.device, g_present.swapchain, id, 20'000'000ull);
        if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) {
            markShown(now());
            return true;
        }
        if (result != VK_TIMEOUT) {
            return false;
        }
        std::lock_guard<std::mutex> lock(g_present.mutex);
        if (g_present.stop) {
            return false;
        }
    }
    return false;
}

// Presenter thread, after a real frame went out.
void recordLatency(double queued) {
    Timing& timing = g_present.timing;
    double t = now();
    timing.latencySum += t - queued;
    timing.latencies++;
    if (timing.windowStart == 0.0) {
        timing.windowStart = t;
    }
    if (t - timing.windowStart >= 1.0) {
        if (timing.intervals > 0) {
            double mean = timing.intervalSum / timing.intervals;
            double variance = timing.intervalSqSum / timing.intervals - mean * mean;
            g_present.intervalMs = static_cast<float>(mean * 1000.0);
            g_present.jitterMs = static_cast<float>(std::sqrt(variance > 0.0 ? variance : 0.0) * 1000.0);
            g_present.worstMs = static_cast<float>(timing.intervalMax * 1000.0);
        }
        g_present.latencyMs = static_cast<float>(timing.latencySum / timing.latencies * 1000.0);
        double lastShow = timing.lastShow;
        timing = Timing{};
        timing.lastShow = lastShow;
        timing.windowStart = t;
    }
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

// CPU wait until the game's submission reached `value`; false if the presenter is stopping.
bool waitRendered(uint64_t value) {
    VkSemaphoreWaitInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
    info.semaphoreCount = 1;
    info.pSemaphores = &g_present.timeline;
    info.pValues = &value;
    for (;;) {
        VkResult result = g_vk.waitSemaphores(g_vk.device, &info, 100'000'000ull);
        if (result == VK_SUCCESS) {
            return true;
        }
        if (result != VK_TIMEOUT) {
            setThreadError("waiting for the rendered frame failed (" + std::to_string(result) + ")");
            return false;
        }
        std::lock_guard<std::mutex> lock(g_present.mutex);
        if (g_present.stop) {
            return false;
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
        g_present.lastTake = now();
        g_present.lastGenerated = job.generated;
        g_present.cv.notify_all();  // the game may start its next frame now

        PresentSlot& slot = g_present.slots[job.slot];
        // With VSync and present wait every frame waits until it is on screen, so the generated and
        // the real frames each get one refresh. Otherwise they are spread evenly over a frame interval.
        bool paceOnDisplay = g_vk.presentWait && g_present.vsync;
        bool shown = waitRendered(job.value);
        double start = now();
        for (int i = 0; shown && i < job.generated; i++) {
            uint64_t id = showImage(slot.interp[i], job.value);
            g_present.generatedCount++;
            if (paceOnDisplay) {
                waitDisplayed(id);
                continue;
            }
            if (g_vk.presentWait) {
                waitDisplayed(id);  // timing only: returns right away without VSync
            }
            bool backlog;
            {
                std::lock_guard<std::mutex> lock(g_present.mutex);
                backlog = !g_present.jobs.empty();
            }
            if (!backlog) {
                sleepUntil(start + g_present.frameInterval.load() * (i + 1) / (job.generated + 1));
            }
        }
        if (shown) {
            uint64_t id = showImage(slot.real, job.value);
            if (g_vk.presentWait) {
                waitDisplayed(id);
            }
            recordLatency(job.queued);
        }
        waitBlits();
        {
            std::lock_guard<std::mutex> lock(g_present.mutex);
            slot.busy = false;
        }
        g_present.cv.notify_all();
    }
}

// ---- Debug capture: the real and generated frames of a few presented frames as PNG files ----------

struct CaptureImage {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    uint64_t ready = 0;  // timeline value after which the copy is complete
    std::wstring path;
    bool motion = false;  // RG16F motion vectors (render pixels), shown as colors
    bool depth = false;   // D32 depth, written as a value grid only
};

float halfToFloat(uint16_t h) {
    uint32_t sign = (h >> 15) & 1, exponent = (h >> 10) & 0x1F, mantissa = h & 0x3FF;
    float value = exponent == 0 ? mantissa / 1024.0f / 16384.0f
        : exponent == 31 ? 65504.0f : (1.0f + mantissa / 1024.0f) * std::ldexp(1.0f, static_cast<int>(exponent) - 15);
    return sign ? -value : value;
}

struct Capture {
    int remaining = 0;
    int frame = 0;
    std::wstring dir;
    std::vector<CaptureImage> pending;
} g_capture;

uint32_t crc32(const uint8_t* data, size_t size) {
    static uint32_t table[256];
    if (!table[1]) {
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t c = i;
            for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
    }
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < size; i++) crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

// RGBA8 rows bottom first (the game's orientation) -> PNG (uncompressed deflate), top row first.
void writePng(const std::wstring& path, const uint8_t* rgba, uint32_t width, uint32_t height) {
    std::vector<uint8_t> raw;
    raw.reserve(static_cast<size_t>(width * 3 + 1) * height);
    for (uint32_t y = 0; y < height; y++) {
        const uint8_t* row = rgba + static_cast<size_t>(height - 1 - y) * width * 4;
        raw.push_back(0);
        for (uint32_t x = 0; x < width; x++) {
            raw.insert(raw.end(), row + x * 4, row + x * 4 + 3);
        }
    }
    std::vector<uint8_t> z = {0x78, 0x01};
    uint32_t a = 1, b = 0;
    for (uint8_t v : raw) {
        a = (a + v) % 65521;
        b = (b + a) % 65521;
    }
    for (size_t pos = 0; pos < raw.size();) {
        size_t n = raw.size() - pos < 65535 ? raw.size() - pos : 65535;
        z.push_back(pos + n == raw.size() ? 1 : 0);
        z.push_back(static_cast<uint8_t>(n & 0xFF));
        z.push_back(static_cast<uint8_t>(n >> 8));
        z.push_back(static_cast<uint8_t>(~n & 0xFF));
        z.push_back(static_cast<uint8_t>((~n >> 8) & 0xFF));
        z.insert(z.end(), raw.begin() + static_cast<std::ptrdiff_t>(pos),
            raw.begin() + static_cast<std::ptrdiff_t>(pos + n));
        pos += n;
    }
    uint32_t adler = (b << 16) | a;
    for (int i = 3; i >= 0; i--) z.push_back(static_cast<uint8_t>(adler >> (i * 8)));

    FILE* f = _wfopen(path.c_str(), L"wb");
    if (!f) {
        return;
    }
    auto be32 = [&](uint32_t v) {
        uint8_t bytes[4] = {uint8_t(v >> 24), uint8_t(v >> 16), uint8_t(v >> 8), uint8_t(v)};
        std::fwrite(bytes, 1, 4, f);
    };
    auto chunk = [&](const char* type, const std::vector<uint8_t>& data) {
        be32(static_cast<uint32_t>(data.size()));
        std::vector<uint8_t> body(type, type + 4);
        body.insert(body.end(), data.begin(), data.end());
        std::fwrite(body.data(), 1, body.size(), f);
        be32(crc32(body.data(), body.size()));
    };
    static const uint8_t signature[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    std::fwrite(signature, 1, 8, f);
    std::vector<uint8_t> header = {uint8_t(width >> 24), uint8_t(width >> 16), uint8_t(width >> 8), uint8_t(width),
        uint8_t(height >> 24), uint8_t(height >> 16), uint8_t(height >> 8), uint8_t(height), 8, 2, 0, 0, 0};
    chunk("IHDR", header);
    chunk("IDAT", z);
    chunk("IEND", {});
    std::fclose(f);
}

// Records a copy of image (in TRANSFER_SRC_OPTIMAL) into a host-visible buffer.
void captureImage(VkCommandBuffer cmd, VkImage image, uint32_t width, uint32_t height, const std::wstring& name,
    VkImageLayout layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, bool motion = false, bool depth = false,
    bool depthAspect = false) {
    CaptureImage out;
    out.motion = motion;
    out.depth = depth;
    out.width = width;
    out.height = height;
    out.path = g_capture.dir + L"\\" + name;
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = static_cast<VkDeviceSize>(width) * height * 4;
    info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if (g_vk.createBuffer(g_vk.device, &info, nullptr, &out.buffer) != VK_SUCCESS) {
        return;
    }
    VkMemoryRequirements requirements;
    g_vk.getBufferMemoryRequirements(g_vk.device, out.buffer, &requirements);
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = requirements.size;
    alloc.memoryTypeIndex = findMemoryType(requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (alloc.memoryTypeIndex == UINT32_MAX || g_vk.allocateMemory(g_vk.device, &alloc, nullptr, &out.memory) != VK_SUCCESS
        || g_vk.bindBufferMemory(g_vk.device, out.buffer, out.memory, 0) != VK_SUCCESS) {
        g_vk.destroyBuffer(g_vk.device, out.buffer, nullptr);
        return;
    }
    VkBufferImageCopy region{};
    region.imageSubresource = {depthAspect ? VkImageAspectFlags(VK_IMAGE_ASPECT_DEPTH_BIT) : VkImageAspectFlags(VK_IMAGE_ASPECT_COLOR_BIT),
        0, 0, 1};
    region.imageExtent = {width, height, 1};
    g_vk.cmdCopyImageToBuffer(cmd, image, layout, out.buffer, 1, &region);
    out.ready = g_present.timelineValue + 1;  // the value this present() hands out
    g_capture.pending.push_back(out);
}

// Writes captures whose frame finished on the GPU (render thread).
void flushCaptures(bool wait) {
    for (size_t i = 0; i < g_capture.pending.size();) {
        CaptureImage& c = g_capture.pending[i];
        VkSemaphoreWaitInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
        info.semaphoreCount = 1;
        info.pSemaphores = &g_present.timeline;
        info.pValues = &c.ready;
        if (g_vk.waitSemaphores(g_vk.device, &info, wait ? 1'000'000'000ull : 0) != VK_SUCCESS) {
            i++;
            continue;
        }
        void* data = nullptr;
        if (g_vk.mapMemory(g_vk.device, c.memory, 0, VK_WHOLE_SIZE, 0, &data) == VK_SUCCESS) {
            if (c.depth) {
                const auto* d = static_cast<const float*>(data);
                FILE* txt = _wfopen(c.path.c_str(), L"w");
                if (txt) {
                    for (int gy = 0; gy < 5; gy++) {
                        uint32_t y = c.height - 1 - (c.height - 1) * gy / 4;
                        for (int gx = 0; gx < 9; gx++) {
                            uint32_t x = (c.width - 1) * gx / 8;
                            std::fprintf(txt, "%.7f ", d[static_cast<size_t>(y) * c.width + x]);
                        }
                        std::fputc('\n', txt);
                    }
                    std::fclose(txt);
                }
            } else if (c.motion) {
                // x -> red, y -> green around mid grey; 4 render pixels of motion = full scale.
                std::vector<uint8_t> rgba(static_cast<size_t>(c.width) * c.height * 4);
                const auto* mv = static_cast<const uint16_t*>(data);
                for (size_t i = 0; i < static_cast<size_t>(c.width) * c.height; i++) {
                    auto channel = [](float v) {
                        float x = 127.5f + v * 32.0f;
                        return static_cast<uint8_t>(x < 0.0f ? 0.0f : x > 255.0f ? 255.0f : x);
                    };
                    rgba[i * 4] = channel(halfToFloat(mv[i * 2]));
                    rgba[i * 4 + 1] = channel(halfToFloat(mv[i * 2 + 1]));
                    rgba[i * 4 + 2] = 128;
                    rgba[i * 4 + 3] = 255;
                }
                writePng(c.path, rgba.data(), c.width, c.height);
                // Exact values on a 9 x 5 grid (row 0 = top of the screen), render pixels.
                FILE* txt = _wfopen((c.path + L".txt").c_str(), L"w");
                if (txt) {
                    for (int gy = 0; gy < 5; gy++) {
                        uint32_t y = c.height - 1 - (c.height - 1) * gy / 4;
                        for (int gx = 0; gx < 9; gx++) {
                            uint32_t x = (c.width - 1) * gx / 8;
                            size_t i = static_cast<size_t>(y) * c.width + x;
                            std::fprintf(txt, "%7.2f,%-7.2f ", halfToFloat(mv[i * 2]), halfToFloat(mv[i * 2 + 1]));
                        }
                        std::fputc('\n', txt);
                    }
                    std::fclose(txt);
                }
            } else {
                writePng(c.path, static_cast<const uint8_t*>(data), c.width, c.height);
            }
        }
        g_vk.destroyBuffer(g_vk.device, c.buffer, nullptr);
        g_vk.freeMemory(g_vk.device, c.memory, nullptr);
        g_capture.pending.erase(g_capture.pending.begin() + static_cast<std::ptrdiff_t>(i));
    }
}

// Watcher thread: render time = frame start until the GPU finished the frame's submission.
void watcherLoop() {
    uint64_t next = 1;
    VkSemaphoreWaitInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
    info.semaphoreCount = 1;
    info.pSemaphores = &g_present.timeline;
    info.pValues = &next;
    for (;;) {
        {
            std::lock_guard<std::mutex> lock(g_present.mutex);
            if (g_present.stop) {
                return;
            }
        }
        VkResult result = g_vk.waitSemaphores(g_vk.device, &info, 50'000'000ull);
        if (result == VK_TIMEOUT) {
            continue;
        }
        if (result != VK_SUCCESS) {
            return;
        }
        double done = now();
        double started = g_present.started[next % Presenter::kStartedRing].exchange(0.0);
        if (started > 0.0 && done > started) {
            double render = done - started;
            double previous = g_present.renderTime.load();
            // Rises at once, falls slowly: a frame that is late costs a whole refresh.
            g_present.renderTime = render > previous ? render : previous * 0.95 + render * 0.05;
        }
        next++;
    }
}

void releaseFrameGeneration() {
    retire(g_present.fg, g_present.fgParams);
    g_present.fg = nullptr;
    g_present.fgParams = nullptr;
    g_present.fgFailed = false;
}

bool ensureFrameGeneration(VkCommandBuffer cmd) {
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
    create.RenderWidth = g_dlss.renderWidth;
    create.RenderHeight = g_dlss.renderHeight;
    result = NGX_VK_CREATE_DLSSG(cmd, 1, 1, &g_present.fg, g_present.fgParams, &create);
    if (NVSDK_NGX_FAILED(result) || !g_present.fg) {
        int available = 0, initResult = 0;
        NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_FrameGeneration_Available, &available);
        NVSDK_NGX_Parameter_GetI(g_vk.capabilities, NVSDK_NGX_Parameter_FrameGeneration_FeatureInitResult, &initResult);
        g_message = ngxError("frame generation creation failed", result) + " | available=" + std::to_string(available)
            + " init=" + ngxError("", static_cast<NVSDK_NGX_Result>(initResult)) + " opticalFlowQueue="
            + (g_vk.opticalFlowFamily != UINT32_MAX ? "yes" : "no") + " missingExt=[" + g_vk.fgExtensionsMissing + "]";
        releaseFrameGeneration();
        g_present.fgFailed = true;
        return false;
    }
    return true;
}

}  // namespace

IRISDLSS_API void irisdlss_presenter_destroy() {
    if (!g_vk.device) {
        return;
    }
    if (!g_capture.pending.empty() && g_present.timeline) {
        flushCaptures(true);
    }
    if (g_present.thread.joinable() || g_present.watcher.joinable()) {
        {
            std::lock_guard<std::mutex> lock(g_present.mutex);
            g_present.stop = true;
        }
        g_present.cv.notify_all();
        if (g_present.thread.joinable()) {
            g_present.thread.join();
        }
    }
    if (g_present.watcher.joinable()) {
        g_present.watcher.join();
    }
    if (g_vk.presentQueue) {
        g_vk.queueWaitIdle(g_vk.presentQueue);
    }
    // Earlier frames of the game may still copy into the slots (render thread: the queue is ours to use).
    if (g_present.timeline) {
        g_vk.queueWaitIdle(g_vk.queue);
    }
    releaseFrameGeneration();
    for (PresentSlot& slot : g_present.slots) {
        destroyLocalImage(slot.real);
        for (LocalImage& interp : slot.interp) {
            destroyLocalImage(interp);
        }
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
    if (g_present.timeline) {
        g_vk.destroySemaphore(g_vk.device, g_present.timeline, nullptr);
        g_present.timeline = VK_NULL_HANDLE;
    }
    g_present.timelineValue = 0;
    g_present.presentId = 0;
    g_present.lastTake = 0.0;
    g_present.frameStart = 0.0;
    g_present.renderTime = 0.0;
    g_present.refreshPeriod = 0.0;
    g_present.refreshChecked = 0.0;
    g_present.timing = Timing{};
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

IRISDLSS_API int32_t irisdlss_presenter_create(uint64_t parentHwnd, uint32_t width, uint32_t height, int32_t vsync) {
    if (!g_vk.ngxInitialized) {
        return fail(IRISDLSS_NGX_INIT_FAILED, "not initialized");
    }
    if (!g_vk.presentQueue) {
        return fail(IRISDLSS_DEVICE_FAILED, "no spare queue in the graphics family for the presenter");
    }
    irisdlss_presenter_destroy();
    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);
    g_present.frequency = static_cast<double>(frequency.QuadPart);
    g_present.width = width;
    g_present.height = height;
    g_present.vsync = vsync != 0;
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
    g_present.timeline = createSemaphore(true);
    for (PresentSlot& slot : g_present.slots) {
        if (!createLocalImage(slot.real, width, height) || !createLocalImage(slot.interp[0], width, height)) {
            irisdlss_presenter_destroy();
            return fail(IRISDLSS_DEVICE_FAILED, "present slot creation failed");
        }
    }
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = g_vk.queueFamily;
    VkCommandBufferAllocateInfo blitAlloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    blitAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    blitAlloc.commandBufferCount = kBlitCount;
    if (!g_present.timeline || g_vk.createCommandPool(g_vk.device, &poolInfo, nullptr, &g_present.blitPool) != VK_SUCCESS
        || (blitAlloc.commandPool = g_present.blitPool,
            g_vk.allocateCommandBuffers(g_vk.device, &blitAlloc, g_present.blitBuffers) != VK_SUCCESS)) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, "presenter command pool failed");
    }
    for (int i = 0; i < kBlitCount; i++) {
        g_present.blitFences[i] = createFence();
        g_present.acquired[i] = createSemaphore(false);
    }
    g_present.timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
        TIMER_ALL_ACCESS);

    std::string error;
    if (!createSwapchain(error) && !error.empty()) {
        irisdlss_presenter_destroy();
        return fail(IRISDLSS_DEVICE_FAILED, error);
    }
    g_present.thread = std::thread(presenterLoop);
    g_present.watcher = std::thread(watcherLoop);
    g_message = "Vulkan presenter " + std::to_string(width) + "x" + std::to_string(height) + " on the game's device"
        + (g_vk.presentWait ? ", paced on present wait" : ", no present wait");
    return IRISDLSS_OK;
}

IRISDLSS_API void irisdlss_presenter_frame_start() {
    if (!g_present.thread.joinable()) {
        return;
    }
    // 1. Nothing may wait in the queue: the presenter takes the previous frame first.
    {
        std::unique_lock<std::mutex> lock(g_present.mutex);
        g_present.cv.wait_for(lock, std::chrono::milliseconds(100),
            [] { return g_present.stop || g_present.jobs.empty() || g_present.threadFailed; });
    }
    // 2. With VSync the presenter needs the next frame after the one it took is on screen: one
    // refresh per shown frame, generated ones included. Start this one so it is done just in time
    // (render time plus a margin), with input as fresh as possible.
    double refresh = g_present.refreshPeriod.load();
    double render = g_present.renderTime.load();
    if (g_present.vsync && g_vk.presentWait && refresh > 0.0 && render > 0.0) {
        double cycle = refresh * (1 + g_present.lastGenerated.load());
        double target = g_present.lastTake.load() + cycle - render * 1.1 - 0.002;
        double wait = target - now();
        if (wait > 0.0 && wait < 0.05) {
            sleepUntil(target);
        }
    }
    g_present.frameStart = now();
}

IRISDLSS_API void irisdlss_presenter_set_vsync(int32_t vsync) {
    bool value = vsync != 0;
    if (g_present.vsync.exchange(value) != value) {
        g_present.recreate = true;
    }
}

IRISDLSS_API void irisdlss_presenter_set_generated(int32_t count) {
    int max = irisdlss_frame_generation_max() - 1;
    count = count > max ? max : count;
    g_present.generatedWanted = count < 1 ? 1 : count;
}

IRISDLSS_API uint64_t irisdlss_presenter_timeline() {
    return reinterpret_cast<uint64_t>(g_present.timeline);
}

IRISDLSS_API int32_t irisdlss_present(uint64_t commandBuffer, const IrisDlssImage* frame, const IrisDlssImage* depth,
    const IrisDlssImage* motion, const IrisDlssImage* hudless, const float* fg, uint64_t* signalValue) {
    *signalValue = 0;
    if (!g_present.thread.joinable()) {
        return fail(IRISDLSS_DEVICE_FAILED, "presenter not running");
    }
    if (g_present.threadFailed) {
        std::lock_guard<std::mutex> lock(g_present.mutex);
        return fail(IRISDLSS_DEVICE_FAILED, g_present.threadError);
    }
    double t = now();
    if (t - g_present.refreshChecked > 1.0) {
        // The window can move between monitors: refresh rate of the one it is on now.
        g_present.refreshChecked = t;
        MONITORINFOEXW monitor{};
        monitor.cbSize = sizeof(monitor);
        DEVMODEW mode{};
        mode.dmSize = sizeof(mode);
        if (GetMonitorInfoW(MonitorFromWindow(g_present.window, MONITOR_DEFAULTTONEAREST), &monitor)
            && EnumDisplaySettingsW(monitor.szDevice, ENUM_CURRENT_SETTINGS, &mode) && mode.dmDisplayFrequency > 1) {
            g_present.refreshPeriod = 1.0 / mode.dmDisplayFrequency;
        }
    }
    if (g_present.lastFrame > 0.0) {
        double dt = t - g_present.lastFrame;
        dt = dt < 1.0 / 1000.0 ? 1.0 / 1000.0 : dt > 0.1 ? 0.1 : dt;
        g_present.frameInterval = g_present.frameInterval.load() * 0.9 + dt * 0.1;
    }
    g_present.lastFrame = t;
    if (t - g_present.statsStart >= 1.0) {
        g_present.fps = static_cast<float>(g_present.shownCount.exchange(0) / (t - g_present.statsStart));
        g_present.fgFactor = g_present.generatedCount.exchange(0) > 0 ? 1 + g_present.lastGeneratedCount : 0;
        g_present.statsStart = t;
    }

    if (!g_capture.pending.empty()) {
        flushCaptures(false);
    }
    int slotIndex = g_present.nextSlot;
    PresentSlot& slot = g_present.slots[slotIndex];
    {
        std::unique_lock<std::mutex> lock(g_present.mutex);
        g_present.cv.wait(lock, [&] { return !slot.busy; });
    }

    auto cmd = reinterpret_cast<VkCommandBuffer>(commandBuffer);
    // Frame generation needs this frame's depth / motion vectors / hudless output at matching sizes.
    bool useFg = fg != nullptr && depth && motion && hudless && g_dlss.handle != nullptr
        && hudless->width == g_present.width && hudless->height == g_present.height && ensureFrameGeneration(cmd);
    int generated = useFg ? g_present.generatedWanted.load() : 0;
    for (int i = 1; i < generated; i++) {
        // The slot is not busy: its images are free to create.
        if (!slot.interp[i].image && !createLocalImage(slot.interp[i], g_present.width, g_present.height)) {
            destroyLocalImage(slot.interp[i]);
            generated = i;
            break;
        }
    }

    VkImageMemoryBarrier copyIn[2] = {
        barrier(*frame, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL),
        barrier(slot.real.image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
    };
    pipelineBarrier(cmd, 2, copyIn);
    VkImageCopy copy{};
    copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.dstSubresource = copy.srcSubresource;
    copy.extent = {frame->width < g_present.width ? frame->width : g_present.width,
        frame->height < g_present.height ? frame->height : g_present.height, 1};
    g_vk.cmdCopyImage(cmd, reinterpret_cast<VkImage>(frame->image), VK_IMAGE_LAYOUT_GENERAL, slot.real.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

    NVSDK_NGX_Result fgResult = NVSDK_NGX_Result_Success;
    if (useFg) {
        std::vector<VkImageMemoryBarrier> toEval = {
            barrier(slot.real.image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
            barrier(*depth, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
            barrier(*motion, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
            barrier(*hudless, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
        };
        for (int i = 0; i < generated; i++) {
            toEval.push_back(barrier(slot.interp[i].image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL));
        }
        pipelineBarrier(cmd, static_cast<uint32_t>(toEval.size()), toEval.data());

        NVSDK_NGX_Resource_VK backbuffer = resource(describe(slot.real, g_present.width, g_present.height), false);
        NVSDK_NGX_Resource_VK depthResource = resource(*depth, false);
        NVSDK_NGX_Resource_VK motionResource = resource(*motion, false);
        NVSDK_NGX_Resource_VK hudlessResource = resource(*hudless, false);
        NVSDK_NGX_VK_DLSSG_Eval_Params eval{};
        eval.pBackbuffer = &backbuffer;
        eval.pDepth = &depthResource;
        eval.pMVecs = &motionResource;
        eval.pHudless = &hudlessResource;
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
        // One evaluation per generated frame: count = generated frames per rendered one, index 1..count.
        for (int i = 0; i < generated && NVSDK_NGX_SUCCEED(fgResult); i++) {
            NVSDK_NGX_Resource_VK interp = resource(describe(slot.interp[i], g_present.width, g_present.height), true);
            eval.pOutputInterpFrame = &interp;
            opt.multiFrameCount = static_cast<unsigned int>(generated);
            opt.multiFrameIndex = static_cast<unsigned int>(i + 1);
            fgResult = NGX_VK_EVALUATE_DLSSG(cmd, g_present.fg, g_present.fgParams, &eval, &opt);
        }

        std::vector<VkImageMemoryBarrier> afterEval = {
            barrier(slot.real.image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
            barrier(*depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
            barrier(*motion, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
            barrier(*hudless, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
            barrier(*frame, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL),
        };
        for (int i = 0; i < generated; i++) {
            afterEval.push_back(barrier(slot.interp[i].image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL));
        }
        pipelineBarrier(cmd, static_cast<uint32_t>(afterEval.size()), afterEval.data());
        if (g_capture.remaining > 0) {
            // Generated frame (between the previous and this real frame), then this real frame.
            wchar_t name[64];
            std::swprintf(name, 64, L"fg_%03d_a_generated.png", g_capture.frame);
            captureImage(cmd, slot.interp[0].image, g_present.width, g_present.height, name);
            std::swprintf(name, 64, L"fg_%03d_b_real.png", g_capture.frame);
            captureImage(cmd, slot.real.image, g_present.width, g_present.height, name);
            std::swprintf(name, 64, L"fg_%03d_c_motion.png", g_capture.frame);
            captureImage(cmd, reinterpret_cast<VkImage>(motion->image), motion->width, motion->height, name,
                VK_IMAGE_LAYOUT_GENERAL, true);
            std::swprintf(name, 64, L"fg_%03d_d_depth.txt", g_capture.frame);
            captureImage(cmd, reinterpret_cast<VkImage>(depth->image), depth->width, depth->height, name,
                VK_IMAGE_LAYOUT_GENERAL, false, true, isDepthFormat(static_cast<VkFormat>(depth->format)));
            g_capture.frame++;
            g_capture.remaining--;
        }
    } else {
        VkImageMemoryBarrier toSource[2] = {
            barrier(slot.real.image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
            barrier(*frame, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL),
        };
        pipelineBarrier(cmd, 2, toSource);
    }

    uint64_t value = ++g_present.timelineValue;
    g_present.started[value % Presenter::kStartedRing] = g_present.frameStart;
    {
        std::lock_guard<std::mutex> lock(g_present.mutex);
        slot.busy = true;
        g_present.jobs.push_back({slotIndex, NVSDK_NGX_SUCCEED(fgResult) ? generated : 0, value, t,
            g_present.frameStart});
    }
    if (generated > 0 && NVSDK_NGX_SUCCEED(fgResult)) {
        g_present.lastGeneratedCount = generated;
    }
    g_present.cv.notify_all();
    g_present.nextSlot = (slotIndex + 1) % kSlotCount;
    *signalValue = value;

    if (NVSDK_NGX_FAILED(fgResult)) {
        // Keep presenting; frame generation stays off until the presenter is recreated.
        g_present.fgFailed = true;
        g_message = ngxError("frame generation evaluate failed", fgResult) + " (" + std::to_string(generated)
            + " generated frames)";
        return IRISDLSS_DLSS_UNAVAILABLE;
    }
    return IRISDLSS_OK;
}

IRISDLSS_API void irisdlss_debug_capture(int32_t frames, const wchar_t* dir) {
    g_capture.remaining = frames;
    g_capture.frame = 0;
    g_capture.dir = dir;
}

IRISDLSS_API void irisdlss_presenter_stats(float stats[IRISDLSS_STATS_COUNT]) {
    stats[0] = g_present.fps;
    stats[1] = static_cast<float>(g_present.fgFactor);
    stats[2] = g_present.threadFailed ? 1.0f : 0.0f;
    double interval = g_present.frameInterval.load();
    stats[3] = interval > 0.0 ? static_cast<float>(1.0 / interval) : 0.0f;
    stats[4] = g_present.intervalMs;
    stats[5] = g_present.jitterMs;
    stats[6] = g_present.worstMs;
    stats[7] = g_present.latencyMs;
}

IRISDLSS_API void irisdlss_shutdown() {
    if (!g_vk.device) {
        return;
    }
    if (g_vk.ngxInitialized) {
        irisdlss_presenter_destroy();
        // The game already waited for its queue; nothing else uses the device any more.
        g_vk.deviceWaitIdle(g_vk.device);
        irisdlss_destroy_resources();
        releaseRetired(true);
        if (g_vk.capabilities) {
            NVSDK_NGX_VULKAN_DestroyParameters(g_vk.capabilities);
            g_vk.capabilities = nullptr;
        }
        NVSDK_NGX_VULKAN_Shutdown1(g_vk.device);
        g_vk.ngxInitialized = false;
    }
    g_vk = Vk{};
}
