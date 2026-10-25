# Iris DLSS Bridge + Iris/Voxy Vulkan port

Two codebases, side by side:

* **this repo** (`iris-dlss`, github.com/laurensanp/iris-dlss): Fabric mod for Minecraft 26.3 that runs
  DLSS Super Resolution + Frame Generation inside the game's own Vulkan renderer (RenderPearl).
  Done and working (0.4.0). See `README.md`.
* **the Iris fork** (github.com/laurensanp/iris-vulkan, cloned next to this repo as `../iris-vulkan`;
  upstream github.com/IrisShaders/Iris branch `26.3`): porting Iris shader packs to Vulkan so they run
  together with DLSS. Plan and milestones are in `docs/vulkan-port.md`. **Read that first.**
  Branches: `vulkan-m2` = current work (M1 + gbuffers, on top of `vulkan-m1`); `vulkan-m1` = M1 only;
  `vulkan` = an earlier, separate start (own SPIR-V compiler/reflection, not rendering yet). `vulkan` and
  `vulkan-m1` both branch off upstream `671a5c877` and touch 11 of the same files; decide with the user
  which to continue before merging anything.
  (`iris-vulkan` is also recorded in this repo as a bare gitlink without `.gitmodules`; work in the
  separate clone, not through that entry.)

Goal in the end: Iris shader packs (Solas, Complementary) + Voxy LODs + DLSS SR/FG, all on Vulkan.

## Status (2026-10-26)

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
* **M2 (gbuffers) done, branch `vulkan-m2`.** `Iris.isPackInUseQuick()` is true on Vulkan again,
  `copyMainColorIntoColortex0` is gone. Verified with the `VulkanTest` pack (gbuffers_basic/textured/terrain
  writing colortex0+1): Sodium terrain, particles and hand draw with the pack's programs, block.properties
  IDs work. Design (`vulkan/VulkanGbuffers`): GL Iris rebinds its framebuffer before each draw, Vulkan can't,
  so world render passes into the main target are redirected to all gbuffer colortex buffers (union of the
  programs' DRAWBUFFERS) + main depth (`MixinFrontendCommandEncoder_Gbuffers`), and every `setPipeline` in
  such a pass is swapped for a variant built for those attachments (`MixinFrontendRenderPass_Gbuffers`):
  the pack program (`VulkanProgram.compileGbuffer`: original pipeline's vertex layout/depth/cull/blend,
  outputs moved to their DRAWBUFFERS attachment, vertex inputs matched by name, unmatched ones stubbed) or,
  without one, the original shaders writing colortex0. `VulkanTransformer` gives gbuffers programs
  vanilla's/Sodium's binding names (DynamicTransforms, Sampler0, u_BlockTex...). Reverse-Z is no issue:
  this Iris inverts depth in the shader transform (`DepthTransformer`), the `UndoReverseZ*` mixins are off.
  **Verified with Solas 3.8** (all gbuffers programs compile): terrain, water, sky + clouds, entities,
  block entities, held items, hand, night sky and rain render with Solas; ~62 fps rendered in the 854x480
  window (RTX 4060 Ti, DLSS SR + FG). Water reflections match an OpenGL reference shot: GL formats without
  alpha (RGB16F...) are RGBA on Vulkan, so those targets start with alpha 1 and never get alpha written
  (`VulkanTextures.keepOpaqueAlpha`; Solas drops reflections where gaux1.a is 0). Vertex inputs declared
  wider than their element are padded like GL (`VulkanProgram.narrowInputs`). Uniform default values and
  the horizon box work. A pack without gbuffers draws the world with the original shaders (the Vulkan
  counterpart of Iris' synthesized fallback programs). Not optimized: per-draw uniform upload.
  The chequered pattern on distant sand with Solas also shows on OpenGL without DLSS (pack, not port).
  Fixed 2026-10-25: block textures looked scrambled with Solas (generated normals) because uniforms were
  uploaded at `setPipeline`, before Sodium binds `u_BlockTex`, so `atlasSize` came from the GUI font
  texture; `VulkanGbuffers.afterAlbedoBound` re-uploads them. Shows best at sunset (`time set 12300`).
  Test options: `-PtestCommands="time set 18000;weather rain;tp @p -36 82 18 180 8"` (run after joining;
  that tp gives the beach view used for GL/Vulkan comparisons), `-PtestPitch=40` (negative looks up).
  OpenGL reference shot: `enabled=false` in `config/iris-dlss.properties` + `preferredGraphicsBackend:"opengl"`
  in `options.txt`, then restore both. `VulkanTestPost` pack: composite/final only, no gbuffers.
* **Complementary Reimagined r5.9.3 runs** on Vulkan (2026-10-25): needed the fallback texture created
  outside render passes, a Vulkan PBR sampler, and `tex` mapped to the albedo binding (`VulkanTransformer`).
* Other packs (2026-10-25, Modrinth downloads in `fabric/run/shaderpacks`): Complementary Unbound, BSL,
  MakeUp Ultra Fast run. Photon, Bliss, Sildur's still fail to compile (set aside by the user; focus is
  Solas); Rethinking Voxels needs CUSTOM_IMAGES/SSBO/COMPUTE (M3). Test script idea: set `shaderPack=` in
  `fabric/run/config/iris.properties`, run the test world, grep the log.
  `enableDebugOptions=true` (dumps `fabric/run/patched_shaders`) makes Solas' deferred1 fail
  (`colortex5` undeclared with SSAO=false); keep it false otherwise.
* Lunar Client: its "Multi-Threaded Rendering" acquires/presents the swapchain itself (mixins on
  `FrontendGpuSurface.acquireNextTexture` / `VulkanGpuSurface.present`), so the DLSS presenter (and FG)
  can't run; the mod detects it, keeps the game's output and says so in the status line. The user plays
  in Lunar (`~/.lunarclient/profiles/surv`). Presenting from both swapchains stalls in fullscreen.
  Dev tests: `fullscreen:false` in `fabric/run/options.txt`, else a focus change minimizes the
  window and Minecraft caps it at 10 fps (looks like a presenter stall).
* Installing into Lunar (`~/.lunarclient/profiles/surv`, only while its game isn't running; check with
  the java processes, the launcher may stay open): fork `gradlew :fabric:build` -> `build/libs/iris-fabric-*.jar`
  (repo root, not `fabric/build`), copied over `mods/iris-fabric-vulkan+mc26.3.jar`; `mod/build/libs/iris-dlss-*.jar`
  over `mods/iris-dlss-0.4.0+mc26.3.jar` (same names, no duplicates). Back up the old jars first
  (`mods-backup-<date>/`). Last install 2026-10-26 (everything up to the settings UI). Lunar's Sodium is
  0.9.3-beta.1 (dev: 0.9.2), it also has ImmediatelyFast, entityculling, dynamic-fps, Xaero's maps.
  The user's Solas settings live in `shaderpacks/Solas Shader V3.8.zip.txt` there (DOF, VL, tonemap
  tweaks, shadowMapResolution 4096) and compile on Vulkan; shadow/voxel/floodfill options wait for M3.
* Known gaps (M3): shadow pass (skipped on Vulkan), geometry/tessellation shaders, compute, images, colour
  space conversion.
  Grep the log for `Not supported on Vulkan yet` and `ignored GlStateManager.` to see what a pack hits.
* The fork is public at github.com/laurensanp/iris-vulkan (LGPL-3.0); upstream Iris is the `upstream` remote.
* `fabric/run` (test world, packs, config) is not in git; the `VulkanTest` pack is a few small own GLSL
  files, recreate it from the description above if missing.

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
* Benchmark: `-PtestWorld=vulkan-test -PdlssBenchmark -PwindowSize=1920x1080` (fork) cycles SR+FG, SR Quality,
  SR Performance, DLAA, off through `DlssConfig` (`DlssBenchmark`, not the settings UI, config file not written)
  and logs `Iris DLSS benchmark:` lines with rendered/shown fps. Solas, 1080p, static view, 4060 Ti (2026-10-25):
  SR+FG 92 rendered/180 shown, SR Quality 142, SR Performance 145, DLAA 80, off 104.
  Not CPU-bound (JFR, 2026-10-26): the render thread mostly waits in RenderPearl's `submit` for the frame
  before last (GPU-bound) and in the presenter's `frameStart`. DLSS SR preset K itself costs ~2 ms at
  1080p on the 4060 Ti (DLAA vs off), FG ~3 ms per rendered frame; render distance 32 -> 12 lifts
  Performance from 145 to 170. Preset E (CNN, added as an option) is ~1 ms cheaper than K. Preset L (12, second-gen
  transformer, added too) is ~1.8 ms more expensive than K at Quality (104 -> 87 fps in one run).
  Preset M (13) at Performance ~0.9 ms more than K. Mode "Custom" (render scale 33-100 %) creates DLSS
  with that size and the closest NGX mode (`Quality.ngxMode`); 80 % = 1536x864 works.
  Settings UI (2026-10-26): Sodium pages "Settings" / "Advanced" (`SodiumOptions`, options grey out via
  `setEnabledProvider`) and the Mod Menu screen (`DlssSettingsScreen`, same options in sections);
  performance overlay in the HUD (`HudMixin`, setting `overlay`). Screenshot them with the fork's
  `-PdlssScreen=mod|sodium|sodium2` (opens the screen after joining, `DevScreens`).
  Dev `options.txt` has `inactivityFpsLimit:"minimized"`, else the AFK limit (30 fps) hits long benchmarks. Run the
  benchmark with nothing else loading the PC (numbers swing ~25 % between runs otherwise).
  Logs written across midnight get lost (log roll), rerun then.
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
