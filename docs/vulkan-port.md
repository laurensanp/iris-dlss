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
