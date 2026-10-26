package com.irisdlss.voxyvulkan;

import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.jetbrains.annotations.Nullable;

/** The shader pack's Voxy programs, as Voxy's Iris integration prepared them for the current pack. */
final class IrisLods {

    private static Object dumped;

    private IrisLods() {
    }

    /** The pack's Voxy pipeline data, null without a pack or without Voxy programs in it. */
    @Nullable
    static IrisVoxyRenderPipelineData data() {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)
            || !(pipeline instanceof IGetIrisVoxyPipelineData holder)) {
            return null;
        }
        IrisVoxyRenderPipelineData data = holder.voxy$getPipelineData();
        if (data != null && data != dumped && Boolean.getBoolean("voxy-vulkan.dumpPatch")) {
            dumped = data;
            VoxyVulkan.LOGGER.info("Voxy Vulkan: pack patch\n--- uniforms ({} bytes)\n{}\n--- images\n{}\n--- opaque draw targets {}, "
                    + "vanilla depth {}, TAA\n{}\n--- opaque patch\n{}", data.getUniforms().size(), data.getUniforms().layout(),
                data.getImageSet().layout(), java.util.Arrays.toString(data.opaqueDrawTargets), data.renderToVanillaDepth,
                data.TAA, data.opaqueFragPatch());
        }
        return data;
    }
}
