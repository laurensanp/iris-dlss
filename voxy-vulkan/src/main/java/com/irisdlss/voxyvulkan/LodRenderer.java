package com.irisdlss.voxyvulkan;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws Voxy's LOD sections in Sodium's opaque terrain pass (render thread). Sections are chosen and
 * meshed by {@link LodScheduler}; here the meshes are uploaded and drawn, one draw per section with its
 * camera-relative offset as push constant.
 */
public final class LodRenderer {

    private static final String NAMESPACE = "voxy-vulkan";
    private static final int INFO_SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec4().putVec4().get();
    /** Meshes uploaded per frame at most (keeps frame times even while a world loads). */
    private static final int UPLOADS_PER_FRAME = 48;

    private static final String VERTEX_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        layout(std140) uniform LodInfo {
            mat4 ProjView;
            vec4 Params; // x: vanilla render distance (blocks), y: daylight
            vec4 FogColor;
            vec4 FogRange; // environmental start/end, render distance start/end
        };
        layout(push_constant) uniform Section {
            vec4 Offset; // section corner relative to the camera
        } section;
        layout(location = 0) in vec3 Position;
        layout(location = 1) in vec4 Color;
        layout(location = 0) out vec4 vertexColor;
        layout(location = 1) out vec3 relativePos;
        void main() {
            vec3 pos = Position + section.Offset.xyz;
            relativePos = pos;
            vertexColor = Color;
            gl_Position = ProjView * vec4(pos, 1.0);
        }
        """;

    private static final String FRAGMENT_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        layout(std140) uniform LodInfo {
            mat4 ProjView;
            vec4 Params;
            vec4 FogColor;
            vec4 FogRange;
        };
        float linearFog(float distance, float start, float end) {
            return clamp((distance - start) / max(end - start, 1e-4), 0.0, 1.0);
        }
        layout(location = 0) in vec4 vertexColor;
        layout(location = 1) in vec3 relativePos;
        layout(location = 0) out vec4 fragColor;
        void main() {
            // Inside the vanilla render distance Sodium's terrain is drawn.
            if (dot(relativePos.xz, relativePos.xz) < Params.x * Params.x) {
                discard;
            }
            float light = floor(vertexColor.a * 255.0 + 0.5);
            float block = floor(light / 16.0);
            float sky = light - block * 16.0;
            float level = max(sky * Params.y, block) / 15.0;
            vec3 color = vertexColor.rgb * mix(0.12, 1.0, level);
            // Same fog as the terrain: spherical for the environment, cylindrical for the render distance.
            float spherical = length(relativePos);
            float cylindrical = max(length(relativePos.xz), abs(relativePos.y));
            float fog = max(linearFog(spherical, FogRange.x, FogRange.y), linearFog(cylindrical, FogRange.z, FogRange.w));
            fragColor = vec4(mix(color, FogColor.rgb, fog * FogColor.a), 1.0);
        }
        """;

    private static final ShaderSource SHADERS = new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            if (!id.toString().equals(NAMESPACE + ":lod")) {
                return null;
            }
            return type == ShaderType.VERTEX ? VERTEX_SHADER : FRAGMENT_SHADER;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return null;
        }

        @Override
        public void close() {
        }
    };

    private record GpuMesh(GpuBuffer buffer, int quads) {
    }

    private static RenderPipeline pipeline;
    private static CompiledRenderPipeline compiled;
    private static boolean failed;
    private static MethodHandle irisShadowPass;
    private static boolean irisChecked;

    private static WorldEngine engine;
    private static LodScheduler scheduler;
    private static final Long2ObjectOpenHashMap<GpuMesh> meshes = new Long2ObjectOpenHashMap<>();
    private static int frames;
    private static long lastLog;
    private static boolean packActive;
    /** Where the LODs end (blocks), 0 while none are drawn; the distance fog moves out to it. */
    private static float lodFogEnd;
    private static boolean loggedProjection;

    private LodRenderer() {
    }

    /** The distance fog's end while LODs are drawn, else 0 (FogRenderer, every frame). */
    public static float fogEnd() {
        return engine != null && !packActive ? lodFogEnd : 0.0F;
    }

    /** Sodium's opaque terrain pass, after Sodium's draws. */
    public static void render(RenderPass pass, ChunkRenderMatrices matrices, CameraTransform camera, FogParameters fog) {
        // With a shader pack the pass is Iris' gbuffers pass: LODs need the pack's Voxy program there (stage 2,
        // not done); drawing them with this pipeline would not match its attachments.
        packActive = IrisLods.packActive();
        if (packActive) {
            IrisLods.data(); // dev: -Dvoxy-vulkan.dumpPatch=true logs the pack's Voxy patch once
        }
        if (failed || packActive || inIrisShadowPass()) {
            return;
        }
        VoxyConfig config = VoxyConfig.CONFIG;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (config == null || !config.enabled || !config.enableRendering || level == null) {
            shutdown();
            return;
        }
        WorldEngine current = WorldIdentifier.ofEngine(level);
        if (current == null) {
            shutdown();
            return;
        }
        if (current != engine) {
            shutdown();
            engine = current;
            engine.acquireRef();
            scheduler = new LodScheduler(engine);
            VoxyVulkan.LOGGER.info("Voxy Vulkan: drawing LODs of {}", WorldIdentifier.of(level));
        }
        engine.markActive();

        double vanillaRadius = minecraft.options.getEffectiveRenderDistance() * 16.0;
        double lodRadius = config.sectionRenderDistance * (32 << WorldEngine.MAX_LOD_LAYER);
        lodFogEnd = (float) lodRadius;
        scheduler.update(camera.x, camera.y, camera.z, vanillaRadius, lodRadius);
        upload();
        long[] selection = scheduler.selection();
        if (++frames % 120 == 0) {
            dropUnselected(selection);
        }
        if (compiled == null && !compile()) {
            return;
        }

        int maxQuads = 0;
        long totalQuads = 0;
        int drawn = 0;
        for (long key : selection) {
            GpuMesh mesh = meshes.get(key);
            if (mesh != null && mesh.quads() > maxQuads) {
                maxQuads = mesh.quads();
            }
            if (mesh != null && mesh.quads() > 0) {
                totalQuads += mesh.quads();
                drawn++;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastLog > 10_000) {
            lastLog = now;
            VoxyVulkan.LOGGER.info("Voxy Vulkan: {} sections selected, {} meshed, {} drawn with {} quads, fog {}..{}",
                selection.length, meshes.size(), drawn, totalQuads, fog.renderStart(), fog.renderEnd());
        }
        if (maxQuads == 0) {
            return;
        }

        RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(maxQuads * 6);
        float daylight = 1.0F - level.getSkyDarken() / 15.0F;
        GpuBufferSlice info;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, INFO_SIZE)
                .putMat4f(projection(matrices.projection()).mul(matrices.modelView()))
                .putVec4((float) Math.max(0.0, vanillaRadius - 16.0), daylight, 0.0F, 0.0F)
                .putVec4(fog.red(), fog.green(), fog.blue(), fog.alpha())
                .putVec4(fog.environmentalStart(), fog.environmentalEnd(), fog.renderStart(), fog.renderEnd())
                .get();
            int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
            info = RenderSystem.getDevice().createCommandEncoder().transientMemory()
                .uploadGpu(data, alignment, GpuBuffer.USAGE_UNIFORM);
        }

        pass.setPipeline(compiled);
        pass.setUniform("LodInfo", info);
        pass.setIndexBuffer(indexBuffer, quadIndices.type());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer offset = stack.malloc(16);
            for (long key : selection) {
                GpuMesh mesh = meshes.get(key);
                if (mesh == null || mesh.quads() == 0) {
                    continue;
                }
                int size = 32 << WorldEngine.getLevel(key);
                offset.clear();
                offset.putFloat((float) ((double) WorldEngine.getX(key) * size - camera.x));
                offset.putFloat((float) ((double) WorldEngine.getY(key) * size - camera.y));
                offset.putFloat((float) ((double) WorldEngine.getZ(key) * size - camera.z));
                offset.putFloat(0.0F);
                offset.flip();
                pass.pushConstants(offset);
                pass.setVertexBuffer(0, mesh.buffer().slice());
                pass.drawIndexed(mesh.quads() * 6, 1, 0, 0, 0);
            }
        }
    }

    /**
     * The terrain's projection with the far plane moved to infinity, so LODs beyond the vanilla render
     * distance are not clipped. Minecraft's Vulkan projection is reverse-Z with depth 0..1:
     * depth = m32 / distance - m22, near = m32 / (1 + m22); infinitely far: m22 = 0, m32 = near.
     */
    private static Matrix4f projection(Matrix4fc terrain) {
        Matrix4f result = new Matrix4f(terrain);
        float m22 = terrain.m22();
        float m32 = terrain.m32();
        if (!loggedProjection) {
            loggedProjection = true;
            VoxyVulkan.LOGGER.info("Voxy Vulkan: terrain projection m22 {} m32 {} m23 {}", m22, m32, terrain.m23());
        }
        if (m22 >= 0.0F && m32 > 0.0F) {
            result.m22(0.0F);
            result.m32(m32 / (1.0F + m22));
        }
        return result;
    }

    private static void upload() {
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            LodScheduler.Result result = scheduler.results.poll();
            if (result == null) {
                return;
            }
            GpuBuffer buffer = null;
            ByteBuffer vertices = result.mesh().vertices();
            if (vertices != null) {
                try {
                    buffer = RenderSystem.getDevice().createBuffer(() -> "Voxy Vulkan LOD section", GpuBuffer.USAGE_VERTEX,
                        vertices);
                } finally {
                    MemoryUtil.memFree(vertices);
                }
            }
            GpuMesh old = meshes.put(result.key(), new GpuMesh(buffer, result.mesh().quads()));
            if (old != null && old.buffer() != null) {
                old.buffer().close();
            }
        }
    }

    private static void dropUnselected(long[] selection) {
        LongOpenHashSet keep = new LongOpenHashSet(selection);
        var iterator = meshes.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!keep.contains(entry.getLongKey())) {
                if (entry.getValue().buffer() != null) {
                    entry.getValue().buffer().close();
                }
                iterator.remove();
            }
        }
    }

    private static boolean compile() {
        try {
            if (pipeline == null) {
                pipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/lod"))
                    .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "lod"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "lod"))
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("LodInfo", UniformType.UNIFORM_BUFFER).build())
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withPushConstantSize(16)
                    .withCull(false)
                    .withColorTargetState(RenderPipelines.SOLID_TERRAIN.getColorTargetStates().get(0))
                    .withDepthStencilState(RenderPipelines.SOLID_TERRAIN.getDepthStencilState())
                    .build();
            }
            compiled = RenderSystem.getDevice().compilePipeline(pipeline, SHADERS, Runnable::run).join().finishCompile();
            return true;
        } catch (RuntimeException e) {
            failed = true;
            VoxyVulkan.LOGGER.error("Voxy Vulkan: LOD pipeline failed, LODs stay off", e);
            return false;
        }
    }

    /** Iris' shadow pass also runs Sodium's terrain pass; LODs stay out of the shadow map for now. */
    private static boolean inIrisShadowPass() {
        if (!irisChecked) {
            irisChecked = true;
            try {
                Class<?> state = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
                irisShadowPass = MethodHandles.publicLookup().findStatic(state, "areShadowsCurrentlyBeingRendered",
                    MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException e) {
                irisShadowPass = null;
            }
        }
        try {
            return irisShadowPass != null && (boolean) irisShadowPass.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Voxy shuts its instance down (leaving the world, quitting): free everything and release the world. */
    public static void shutdown() {
        if (scheduler != null) {
            scheduler.close();
            scheduler = null;
        }
        if (engine != null) {
            engine.releaseRef();
            engine = null;
        }
        for (GpuMesh mesh : meshes.values()) {
            if (mesh.buffer() != null) {
                mesh.buffer().close();
            }
        }
        meshes.clear();
    }
}
