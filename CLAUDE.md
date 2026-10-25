# Iris DLSS Bridge + Iris/Voxy Vulkan port

Two codebases, side by side:

* **this repo** (`iris-dlss`, github.com/laurensanp/iris-dlss): Fabric mod for Minecraft 26.3 that runs
  DLSS Super Resolution + Frame Generation inside the game's own Vulkan renderer (RenderPearl).
  Done and working (0.4.0). See `README.md`.
* **the Iris fork** (`../iris-vulkan`, upstream github.com/IrisShaders/Iris branch `26.3`, our work on
  branch `vulkan`): porting Iris shader packs to Vulkan so they run together with DLSS. Plan and
  milestones are in `docs/vulkan-port.md`. **Read that first.**

Goal in the end: Iris shader packs (Solas, Complementary) + Voxy LODs + DLSS SR/FG, all on Vulkan.

## Status (2026-10-25)

Milestones from `docs/vulkan-port.md`: M1 composite/final on Vulkan, M2 gbuffers, M3 shadows/compute/
rest, M4 Voxy (own renderer; Voxy is All-Rights-Reserved, don't modify or ship its code).

M1 in progress, in the fork:

* Done and verified in game (screenshot): with the `VulkanTest` pack on Vulkan + Sodium + DLSS SR, the
  vanilla world renders correctly, the pack's composite pass swaps red/blue and its final pass draws
  the 8px border from `viewWidth`/`viewHeight`. So composite/final programs compile through RenderPearl,
  get their uniform block and samplers, and render over the world.
* How M1 hands the world to the pack: on Vulkan `Iris.isPackInUseQuick()` is false (world set up as
  without a pack: vanilla vertex formats, no gbuffers swap, Sodium COMPACT format), and
  `IrisRenderingPipeline.copyMainColorIntoColortex0` copies the main target into colortex0 before
  deferred and composite. M2 replaces exactly these pieces.
* **M1 done (verified):** Solas 3.8 (from Modrinth, in `fabric/run/shaderpacks`, select with
  `shaderPack=Solas Shader V3.8.zip`) runs its whole deferred/composite/final chain on Vulkan over the
  vanilla world with DLSS SR, ~116 fps in the 854x480 dev window, no errors. Only log warnings left:
  compute `shadowcomp`, custom 3D images (voxel flood-fill light; stubbed to read zero). Solas'
  lighting looks "flat" because its gbuffers data (normals, materials) only exists after M2.
* M1 pieces worth knowing: sampler/UBO lists come from SPIR-V reflection (`VulkanProgram.reflectBindings`,
  RenderPearl wants the layout to match the SPIR-V exactly); colortex0 is filled by a blit when formats
  differ; render targets have full mip chains (`VulkanDraw.generateMipmaps`, passes write mip 0 views);
  3D samplers / images are replaced by zero-returning stubs in `VulkanTransformer`; compute programs are
  skipped via `VulkanUnsupported.computeSkipped`.
* **Next: M2 (gbuffers).** Make `Iris.isPackInUseQuick()` true on Vulkan again and replace its GL
  consumers: `MixinShaderManager_Overrides.redirectIrisProgram` must return Vulkan pipelines built from the
  pack's gbuffers programs (`ShaderKey` map in `IrisRenderingPipeline`, currently returns null on Vulkan),
  rendering into the pack's colortex framebuffers instead of the main target; Sodium terrain
  (`compat/sodium`) needs the same with Iris' extended vertex format; then drop
  `copyMainColorIntoColortex0`. Watch reverse-Z (Iris' `UndoReverseZ*` mixins are GL-only).
* Known gaps: geometry/tessellation shaders, compute, images, colour space conversion, horizon box,
  uniform default values (`uniform float x = 1.0;` starts at 0), per-buffer blend overrides.
  Grep the log for `Not supported on Vulkan yet` and `ignored GlStateManager.` to see what a pack hits.
* Not committed yet / not pushed anywhere: the fork has no remote of ours. Ask the user before
  pushing it anywhere (it's LGPL-3.0, a public fork is fine license-wise).

## Fork design (Iris on Vulkan)

Iris above `net.irisshaders.iris.gl` talks in GL ints (program ids, uniform locations, texture ids
behind `IntSupplier`). We keep those ints and swap only the leaves when `IrisVulkan.isActive()`:

| GL concept | Vulkan replacement (package `net.irisshaders.iris.vulkan`) |
|---|---|
| texture names | `VulkanTextures` id table (Iris targets + game textures via `MixinGpuTexture2.iris$getGlId`) |
| `glBindTexture`/`glBindSampler` units | `VulkanUnits` tables |
| sampler objects | `VulkanSamplers` (params -> RenderPearl `GpuSampler`) |
| program objects | `VulkanProgram` (GLSL kept, pipelines compiled lazily per attachment formats + blend) |
| uniform location | std140 byte offset in the generated `IrisUniforms` block; samplers = `SAMPLER_LOCATION_BASE + i` |
| framebuffers | `GlFramebuffer` only records attachments on Vulkan; `VulkanDraw.fullscreen` opens render passes |
| `GlStateManager.*` | every public method returns immediately on Vulkan (ASM guard `VulkanGlGuardTransformer`, applied from `IrisMixinPlugin.postApply`); ignored calls logged once each |

Shader side: `VulkanTransformer` (runs last in `TransformPatcher`) moves all loose uniforms of all stages
into one `IrisUniforms` block, gives vertex inputs locations, renames `gl_VertexID`/`gl_InstanceID`;
upstream's `LayoutTransformer` (now enabled on Vulkan) gives varyings locations.

Rules:
* New GL call sites get a Vulkan branch in the leaf (IrisRenderSystem, GlFramebuffer, ...), not in callers.
* Direct LWJGL GL calls (`GL11C.gl*`, `GL46C.gl*`) **abort the JVM** on Vulkan ("No context is current"),
  so they must be guarded. `GlStateManager` calls are already safe (guard).
* Features we can't run yet: call `VulkanUnsupported.once("...")` and skip, don't fail the pack.
* RenderPearl limits: only vertex + fragment stages (no geometry/tessellation), no compute, no storage
  images/SSBOs, no sampler compare mode (hardware shadows). These need raw Vulkan through a native layer
  like `dlss-bridge` (M3).
* On Vulkan the depth buffer is reverse-Z, 0..1 (see README); Iris' `UndoReverseZ*` mixins are GL-only.
* `IrisMixinPlugin`: on Vulkan all mixins apply except `VKOnly*`; it treats "iris-dlss installed with
  `enabled` not false in `config/iris-dlss.properties`" as Vulkan, because this mod forces Vulkan.

## Build & test

* Gradle needs **JDK 25** (`JAVA_HOME`); Gradle downloads one into `~/.gradle/jdks`.
* This mod: `dlss-bridge/fetch-deps.ps1`, `dlss-bridge/build.bat` (MSVC 2022, Build Tools are enough),
  then `cd mod && gradlew build`. Needs `libs/sodium-fabric-0.9.2+mc26.3.jar` (copy from the Gradle cache
  after building the fork once).
* Fork: `gradlew :fabric:build` (jar in `build/libs`), `gradlew :fabric:runClient`.
* Test loop in the fork: copy `mod/build/libs/iris-dlss-*.jar` into `../iris-vulkan/fabric/run/mods/`, then
  `gradlew :fabric:runClient -PtestWorld=vulkan-test`: joins (first time: creates, fixed seed) the world,
  saves `fabric/run/screenshots/vulkan-test.png` after 200 ticks and quits (mixins in `mixin/vulkan/`).
  Look at the screenshot. `fabric/run/config/iris.properties` `shaderPack=VulkanTest` selects the test pack
  in `fabric/run/shaderpacks/VulkanTest` (composite swaps red/blue, final draws an 8px red border from
  `viewWidth`/`viewHeight`; both must show up when M1 works). `shaderPack=` (empty) = no pack.
* Pipeline creation failures are caught by Iris ("Failed to create shader rendering pipeline") and logged
  with a stack trace in `fabric/run/logs/latest.log`; fatal GL calls show up as `FATAL ERROR in native
  method` in the Gradle output instead.
* `-PdlssDebug=spin|walk` turns on this mod's capture/motion debugging; its frame captures only fire when
  Frame Generation runs.

## Hardware

* DLSS Frame Generation needs an RTX 40/50 card. On an RTX 3070 (one of the two dev machines) SR works,
  FG fails with `NVSDK_NGX_Result_FAIL_UnableToInitializeFeature` - expected, not a bug. Verify FG on the
  RTX 40+ machine; `dlss-bridge/out/dlss_probe.exe` reports SR/FG availability without the game.

## Working with the user

* Verify every milestone with frame captures/screenshots, as described above.
* Ask before pushing, and before cloning/downloading third-party code (shader packs too: Solas and
  Complementary are free to download but not ours to redistribute).
