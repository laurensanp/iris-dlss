package com.siris;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;

/**
 * diagnosticRecording=true also counts the textures and buffers created on the game's Vulkan device, by label,
 * and logs the biggest every 10 seconds ("SIRIS GPU allocations:"), with the video memory RenderPearl's
 * allocator (VMA) holds and what the whole game process uses (VK_EXT_memory_budget). Finds what fills video memory.
 */
public final class GpuAllocationStats {

    private static final long INTERVAL_NANOS = 10_000_000_000L;
    private static final Map<String, long[]> BY_LABEL = new ConcurrentHashMap<>();
    private static final AtomicLong WINDOW_START = new AtomicLong(System.nanoTime());
    private static volatile VulkanDevice device;

    private GpuAllocationStats() {
    }

    public static boolean enabled() {
        return DlssConfig.diagnosticRecording;
    }

    /** VulkanDevice constructor. */
    public static void device(VulkanDevice created) {
        device = created;
    }

    /** Render thread, every frame. */
    public static void frame() {
        if (!enabled()) {
            return;
        }
        long start = WINDOW_START.get();
        long now = System.nanoTime();
        if (now - start >= INTERVAL_NANOS && WINDOW_START.compareAndSet(start, now)) {
            report((now - start) / 1e9);
        }
    }

    public static void texture(String label, int width, int height, int depth, int mips, int bytesPerTexel) {
        // A full mip chain adds a third.
        long bytes = (long) width * height * Math.max(1, depth) * bytesPerTexel * (mips > 1 ? 4 : 3) / 3;
        add("texture " + label, bytes);
    }

    public static void buffer(Supplier<String> label, long bytes) {
        String name;
        try {
            name = label == null ? "?" : label.get();
        } catch (RuntimeException e) {
            name = "?";
        }
        add("buffer " + name, bytes);
    }

    private static void add(String label, long bytes) {
        long[] entry = BY_LABEL.computeIfAbsent(label, k -> new long[2]);
        synchronized (entry) {
            entry[0]++;
            entry[1] += bytes;
        }
    }

    private static void report(double seconds) {
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(BY_LABEL.entrySet());
        BY_LABEL.clear();
        long total = 0;
        long count = 0;
        for (Map.Entry<String, long[]> e : entries) {
            total += e.getValue()[1];
            count += e.getValue()[0];
        }
        entries.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder line = new StringBuilder(String.format("SIRIS GPU allocations: %.0f MB in %d allocations over %.0f s; top:",
            total / 1048576.0, count, seconds));
        for (int i = 0; i < Math.min(8, entries.size()); i++) {
            Map.Entry<String, long[]> e = entries.get(i);
            line.append(String.format(" [%s: %dx, %.1f MB]", e.getKey(), e.getValue()[0], e.getValue()[1] / 1048576.0));
        }
        line.append(" | ").append(memoryLine());
        Siris.LOGGER.info(line.toString());
    }

    /** "VMA 1234 MB, process 2345 MB / budget 7000 MB" for the device-local heaps. */
    private static String memoryLine() {
        VulkanDevice d = device;
        if (d == null) {
            return "no device";
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.malloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(d.vkDevice().getPhysicalDevice(), props);
            int heaps = props.memoryHeapCount();
            VmaBudget.Buffer budgets = VmaBudget.calloc(VK10.VK_MAX_MEMORY_HEAPS, stack);
            Vma.vmaGetHeapBudgets(d.vma(), budgets);
            VkPhysicalDeviceMemoryBudgetPropertiesEXT ext = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack)
                .sType$Default();
            VkPhysicalDeviceMemoryProperties2 props2 = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default()
                .pNext(ext.address());
            VK11.vkGetPhysicalDeviceMemoryProperties2(d.vkDevice().getPhysicalDevice(), props2);
            long vma = 0;
            long process = 0;
            long budget = 0;
            for (int i = 0; i < heaps; i++) {
                if ((props.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) {
                    continue;
                }
                vma += budgets.get(i).statistics().blockBytes();
                process += ext.heapUsage(i);
                budget += ext.heapBudget(i);
            }
            return String.format("device-local memory: VMA %d MB, game process %d MB, budget %d MB", vma >> 20,
                process >> 20, budget >> 20);
        } catch (RuntimeException | LinkageError e) {
            return "memory query failed: " + e;
        }
    }
}
