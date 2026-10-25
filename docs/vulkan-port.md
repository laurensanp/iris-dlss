# Iris + Voxy on Vulkan: port plan

Goal: Iris shader packs (e.g. Solas, Complementary) and Voxy LODs on Minecraft 26.3's Vulkan backend,
with DLSS SR + Frame Generation from this mod on top. No OpenGL.

## Why it is a port, not a patch

Measured in the 26.3 builds (Iris 1.11.7, Voxy 0.2.20):

| Mod | Classes | Use OpenGL directly | Use RenderPearl |
|-----|---------|---------------------|-----------------|
| Iris | 983 | 79: shader programs, framebuffers, textures, loose uniforms, compute, image binding (the whole shader-pack pipeline) | 163 (vanilla integration) |
| Voxy | 363 | 47: DSA, compute, indirect multi-draw, SSBOs, plus 47 GLSL 4.6 shaders | 11 |

Voxy refuses the Vulkan backend outright ("Voxy does not presently support vulkan"); Iris runs only on
OpenGL.

## Licenses

* Iris: LGPL-3.0-only. A fork with a Vulkan backend is allowed (source stays LGPL).
* Voxy: All-Rights-Reserved. Its code may not be modified or redistributed. On Vulkan it needs a
  renderer of our own that reads Voxy's LOD data (or Voxy stays out).

## Approach

Fork Iris (branch `26.3`) and replace its OpenGL backend:

* Shader programs → RenderPearl `RenderPipeline`s; pack GLSL goes through Iris' transformer, then
  RenderPearl's GLSL → SPIR-V compiler (shaderc). Loose uniforms → uniform buffers, samplers →
  bind groups.
* Framebuffers / render targets (colortex*, depthtex*, shadowtex*) → RenderPearl textures and render
  passes.
* Compute shaders and image load/store (RenderPearl has no compute API) → raw Vulkan through the
  dlss-bridge-style native layer on the game's device.
* Terrain: Sodium 0.9 already renders on Vulkan; Iris' Sodium integration must inject the gbuffers
  programs into Sodium's Vulkan pipelines.

## Milestones (each verified with frame captures, `-PdlssDebug`)

1. **M1** Iris starts on Vulkan; a pack's post-processing (composite*, final) runs on Vulkan over the
   vanilla-rendered world.
2. **M2** gbuffers: terrain (Sodium), entities, block entities, hand with pack shaders.
3. **M3** shadow pass, deferred passes, custom textures, compute, remaining pack features until Solas
   renders correctly.
4. **M4** Voxy LODs on Vulkan (own renderer over Voxy's data).
5. DLSS SR + FG on top (already Vulkan-native in this mod).

## M1 design

Fork: `IrisShaders/Iris` branch `26.3` (head `671a5c877`), work on branch `vulkan`, cloned next to
this repo (`../iris-vulkan`).

What 26.3 Iris does on Vulkan today: `IrisMixinPlugin` reads `preferredGraphicsBackend` from
`options.txt`; on Vulkan only the `VKOnly_*` mixins apply and the shader screen just offers switching
to OpenGL. This mod forces Vulkan regardless of that option, so the fork also treats "iris-dlss
installed with `enabled` not false" as Vulkan (first change on branch `vulkan`).

RenderPearl (26.3) covers everything composite/final need, no native code:

* `GpuDevice.compilePipeline(RenderPipeline, ShaderSource, Executor)` takes our own `ShaderSource`:
  Iris serves the transformed pack GLSL under synthetic ids (`iris:pack/composite1`), RenderPearl's
  `GlslCompiler` (shaderc, auto-bind uniforms) makes SPIR-V.
* `BindGroupLayout`: `COMBINED_IMAGE_SAMPLER`, `UNIFORM_BUFFER`, `TEXEL_BUFFER`, bound by name with
  `RenderPass.setUniform(name, ...)`. No storage images / SSBOs / compute: those stay for M3 (native).
* `RenderPassDescriptor`: several color attachments + depth, so colortex MRT (`DRAWBUFFERS`) maps 1:1.
* `GpuFormat` has every Iris `InternalTextureFormat` that matters (RGBA16F, R11G11B10F, RGB10A2, ...).
* Mipmapped colortex (`*MipmapEnabled`): no mipmap API; a downsample blit chain per level.

GLSL rules of RenderPearl's compiler (from the vanilla core shaders): `#version 330`, explicit
`layout(location = n)` on every varying (`GL_ARB_separate_shader_objects`), std140 named uniform
blocks, loose `sampler2D` allowed, `gl_VertexIndex`. On top of Iris' existing core-profile transform
the Vulkan path therefore adds, in glsl-transformer:

1. loose non-opaque uniforms -> one `layout(std140) uniform IrisUniforms { ... }`; the std140 offset of
   each member is its "location";
2. explicit locations for every `in`/`out` varying, matched by name between stages;
3. `gl_VertexID` -> `gl_VertexIndex`, `gl_FragData[n]` / `outColorN` -> `layout(location = n)` outputs.

Java side: Iris above `net.irisshaders.iris.gl` talks in GL ints (program ids, uniform locations,
texture ids as `IntSupplier`). Keep those ints and port only the leaves:

| Leaf | Vulkan replacement |
|------|--------------------|
| `GlShader`, `ProgramCreator`, `ProgramBuilder` | `RenderPipeline` + `CompiledRenderPipeline`, program id -> registry entry |
| `ProgramUniforms` / `IrisRenderSystem.uniform*` | write into the program's CPU copy of `IrisUniforms` at the reflected offset, upload once per draw (`TransientMemory`) |
| `ProgramSamplers` | texture unit -> sampler name, `setUniform(name, view, sampler)` |
| `GlTexture`, `RenderTarget`, `DepthTexture` | `GpuTexture` / `GpuTextureView`, texture id -> registry entry |
| `GlFramebuffer` | list of views -> `RenderPassDescriptor` |
| `CompositeRenderer` / `FinalPassRenderer` draw | render pass + `draw(0, 3, 0, 1)` fullscreen triangle |

Order of work: (a) Iris loads on Vulkan with the pipeline disabled (no GL calls reached),
(b) registries + render targets, (c) one composite pass of a trivial test pack, (d) the full
composite/final chain of Solas over the vanilla world, verified with captures.

**Status 2026-10-25: M1 done.** (a)-(d) verified with in-game screenshots: Solas 3.8's full
deferred/composite/final chain runs on Vulkan + Sodium + DLSS SR (RTX 3070, ~116 fps in the dev window).
Still skipped until M3: compute (`shadowcomp`) and 3D custom images (stubbed to read zero). Details and
the M2 entry points are in `CLAUDE.md`.
