# voxy-vulkan stage 2: LODs with the shader pack's Voxy programs

State 2026-10-27: stage 1 (own LOD renderer, no pack) works, see CLAUDE.md. With Solas active the game
runs (Voxy's GL Iris hook `voxy$injectIrisCompat` is skipped on Vulkan), but the LODs are not drawn:
the fork's gbuffers pass only swaps pipelines it knows from `RenderSystem`
(`VulkanGbuffers.adapt`: "a pipeline that didn't come from RenderSystem in a gbuffers pass").

## What a pack's Voxy program expects (Solas 3.8)

`shaders/voxy.json`:

- `opaquePatchData` / `translucentPatchData`: GLSL that defines
  `void voxy_emitFragment(VoxyFragmentParameters parameters)` (Solas: `#include "/voxy_opaque.glsl"` ->
  `programs/voxyOpaque.glsl`). Fields used: `sampledColour` (vec4, texture sample), `tinting` (vec4),
  `lightMap` (vec2, 0..1), `face` (0..5; `face >> 1`: 0 = y, 1 = z, 2 = x axis; `face & 1 == 0`: negative
  direction), `customId` (block.properties id of the block).
- The patch writes its own outputs (`layout(location = n) out vec4 outN`), mapped to `opaqueDrawBuffers`
  (Solas: [0, 3]) / `translucentDrawBuffers` ([0, 1, 7]); `blending` per buffer.
- `uniforms`: names the patch uses without declaring them (standard Iris uniforms plus Voxy's
  `vxViewProj`, `vxViewProjInv`, `vxViewProjPrev`, `vxModelView`, `vxModelViewInv`, `vxModelViewPrev`,
  `vxProj`, `vxProjInv`, `vxProjPrev`, `vxRenderDistance`). The program must declare them with Iris' types.
- `samplers`: name -> type (depthtex0/1, gaux1/2, noisetex, shadowtex0/1, shadowcolor0, `vxDepthTexOpaque`,
  `vxDepthTexTrans`).
- `taaOffset`: function body returning the pack's TAA jitter (Solas: 0 with TAA off, which DLSS needs).
- `excludeLodsFromVanillaDepth: true`: LOD depth goes to `vxDepthTexOpaque`, not depthtex0. Solas' deferred
  and composite passes read `vxDepthTexOpaque` (and the vx matrices) to find LOD pixels.

## Voxy's own Iris integration (Voxy jar, runs on Vulkan already)

CPU-side mixins that already apply with our fork: `MixinProgramSet` (parses the patch: `IrisShaderPatch`),
`MixinStandardMacros` (VOXY defines), `MixinIrisSamplers` (`VoxySamplers.addSamplers`),
`MixinMatrixUniforms` (vx* uniforms), `MixinIrisRenderingPipeline` (`IrisVoxyRenderPipelineData.buildPipeline`).
Their values (depth textures, matrices) come from Voxy's GL renderer, which is never created on Vulkan.

## Plan

1. Fork: an LOD program built from our vertex shader + our `main` (fills `VoxyFragmentParameters` from the
   vertex data) + the pack's patch text, read through the ProgramSet's source provider (options applied,
   includes resolved); undeclared uniforms declared from the `uniforms` list with the types Iris registers
   them with (capture them through a UniformHolder); samplers from the `samplers` list.
2. voxy-vulkan: a separate render pass before Sodium's terrain: the pack's gbuffer attachments for the
   patch's draw buffers + an own depth texture (reverse-Z, cleared to 0) that becomes `vxDepthTexOpaque`;
   vertex data extended by face, block id (block.properties via Iris' block id map) and lightmap.
3. Values of the vx* uniforms and `vxDepthTex*` samplers from our renderer (either by feeding Voxy's
   suppliers or by registering our own in the fork); decide after reading `VoxySamplers` / Voxy's uniform
   suppliers.
4. Verify against Voxy on OpenGL with the same pack and view (GL reference: Voxy works there).
