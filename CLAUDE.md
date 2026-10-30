# Iris DLSS Bridge

One repository (github.com/laurensanp/iris-dlss, MIT): a Fabric mod for Minecraft 26.3 that runs DLSS Super
Resolution + Frame Generation inside the game's own Vulkan renderer (RenderPearl). Shader packs come from
**Vitrail** (separate mod, modrinth `vitrail-shaders`, github avpbynf/Vitrail-Shaders, LGPL-3.0, not ours,
never commit or ship it). `README.md` lists everything a player or developer needs and where it comes from.

Goal: shader packs (Solas) + DLSS SR/FG on Vulkan, in the user's Lunar Client. Voxy LODs (voxy-vulkan) paused.

| Path | What |
|---|---|
| `dlss-bridge/` | native C++ bridge (`irisdlss.dll`): NGX, DLSS SR, presenter + DLSS-G, `dlss_probe.exe` |
| `mod/` | the Fabric mod (Java), packs the bridge + NVIDIA DLLs into its jar |
| `voxy-vulkan/` | separate mod: Voxy LODs drawn on Vulkan (paused) |
| `testpacks/` | own small GLSL test packs (from the old Iris port) |
| `libs/` (ignored) | compile-only jars: `sodium-fabric-0.9.2+mc26.3.jar`, `voxy-0.2.20-beta.jar` (Modrinth) |
| `mod/run/` (ignored) | test game dir: Sodium, Fabric API, Vitrail in `mods/`, packs, `saves/vulkan-test`, `vitrail/pack.txt` |

## History

- Until 2026-10-27 shader packs ran through an own Vulkan port of Iris (github.com/laurensanp/iris-vulkan,
  LGPL, milestones M1-M3 done: gbuffers, shadows, compute, SSBOs, custom images, geometry/tessellation).
  Replaced by Vitrail on 2026-10-27 (5-10 % faster, same picture, maintained by others); the fork's GitHub repo
  is archived, the local clone and the submodule are gone. Releases iris-dlss v0.5.0/v0.6.0 and iris-vulkan
  vulkan-v0.1.0/v0.2.0 belong to that era.
- History of iris-dlss keeps Kiwious' Claude co-author lines (user decided not to rewrite).

## Status (2026-10-30)

- **DLSS with Vitrail works** (Vitrail 0.13.0-beta, Solas 3.8, verified with screenshots, an FG capture and a
  benchmark): Vitrail draws the world into the main target (reverse-Z depth kept), takes the projection the game
  draws with (our jitter included) and runs composite/final before our upscale at `FogRenderer.endFrame`.
  iris-dlss changes for it: `VitrailRenderScaleMixin` (`@Pseudo`, Vitrail's own FSR "Render Scale" reads 100 %
  while DLSS runs; logged once), `ShaderPackTweaks` reads `vitrail/pack.txt` (`pack=`, `enabled=`,
  `renderscale=`, `shadowdistance=`, `shadowmapscale=`; old files: one bare word, `none` = off) and the shared
  `shaderpacks/<pack>.txt`. Pack screen: key `I`.
- Vitrail drops the whole pack when one program doesn't compile (it doesn't remove unused functions like Iris):
  Solas with `SSAO=false` (deferred1, colortex5) or `DOF=true`+`BLOOM=false` (composite14,
  gbufferProjectionInverse). Log: `(Vitrail) <program> did not compile, nothing of this pack will be drawn`.
  Vitrail's hand is too bright at night. Could be reported upstream (ask the user first).
- Benchmark 2026-10-27, 1080p, Solas, render distance 8, static view, 4060 Ti, rendered fps: SR+FG 122 (245 shown),
  Quality 176, Performance 217, DLAA 123, off 146 (old fork in the same scene: 115/161/196/114/133).
- voxy-vulkan (paused): stage 1 (no shader pack) works; with a pack active the LODs are skipped
  (`ShaderPacks.active()` = Vitrail's `PackChain.drawingPack()` by reflection). Design: Voxy's CPU side used as is;
  `VoxyVulkanMixinPlugin.postApply` turns off Voxy's Vulkan device check and its GL Iris hook, `VoxyClientMixin`
  replaces `initVoxyClient`, `VoxyConfigMixin` keeps Voxy's GL renderer off; own `LodScheduler`/`LodMesher`/
  `LodRenderer` drawn at the end of Sodium's SOLID pass, fog pushed out (`FogRendererMixin`). Section voxel index
  `y<<10|z<<5|x`, mapping id: light bits 56-63, biome 47-55, block 27-46. Voxy is All-Rights-Reserved: never pack
  or modify its code.
- **Solved (2026-10-27):** in Lunar the FPS dropped after some playtime and recovers later (old fork build and Vitrail alike).
  2026-10-27 finding: Lunar gives the game 3 GB heap ("Allocated Memory: 3072" in crash headers), runs ZGC; on a
  server with Solas the old generation grows ~10 MB/s to ~2.3 GB plus ~600 MB young, so the heap sits at the limit
  and ZGC allocation stalls show as Lunar's `Pause - Unknown` 50-90 ms hitches; rendered fps 86 -> 17, single frames
  up to 1 s. Fixed by the user setting 8 GB in Lunar's launcher (jstat then shows ~16 GB max): old gen stays
  ~560-740 MB, no `Pause - Unknown` after joining, 54-82 fps rendered / 130-165 shown on the server (~4 min watched).
  If it comes back: find the allocator with JFR (`-XX:StartFlightRecording` in Lunar's JVM arguments; Lunar sets
  -XX:+DisableAttachMechanism, but `jstat -gc <pid>` works). Not a drop: "1 fps shown, ~12-18 rendered, pacing
  1000 ms" = window in the background, throttled by Lunar's dynamic-fps mod.

- **Frame pacing without VSync fixed (2026-10-27, `fd1f0cc`)**: PresentMon (Intel, `../presentmon`, needs admin:
  `capture-lunar.bat` as administrator records javaw.exe 60 s) in Lunar, 2x FG, 165 Hz, no VSync: frames go out as
  "Hardware: Legacy Flip", none dropped, but generated/real came in 2.0 / 7.3 ms pairs (15 % visible only 0-2 ms).
  The presenter skipped the real frame's wait whenever the game's next frame was queued (always without VSync).
  Now it always waits 90 % of its slot: Lunar 5.5 / 6.0 ms pairs, 1 % below 2 ms. Two other approaches (minimum gap
  across frames, spacing by game work time) collapsed to ~15 fps (presenter wait feeds back into the game's frame
  interval), don't retry them.
- **Vertex buffer churn fixed (`abfbfc5`)**: `StagedVertexBufferPoolMixin` keeps recycled vanilla vertex buffers
  (GUI, entities, particles) up to 120 frames instead of closing them each frame (with the presenter the GPU is ~2
  frames behind, so fences rarely signalled in time): dev ~2400 buffers / 600 MB per 10 s -> 6 / 4 MB. Lunar's
  remaining FPS drops were full video memory: render distance 32 (~3.8 GB Sodium) + Discord Clips (+ its capture in
  dwm, ~1.5 GB); user turned Discord Clips off. `diagnosticRecording=true` logs GPU allocations by label plus VMA and
  process memory every 10 s (`GpuAllocationStats`).

## Lunar Client

- The user plays in Lunar, profile `~/.lunarclient/profiles/surv` (Fabric 26.3; Lunar's Sodium 0.9.3-beta.1,
  Fabric API, Mod Menu, ImmediatelyFast, entityculling, dynamic-fps, Xaero's maps, nofog, voxy-server-side).
- Install only while Lunar's game isn't running (check the java processes, the launcher may stay open). Back up
  the mods folder first (`mods-backup-<date>-<what>/`), then replace jars with the same names, no duplicates.
- Lunar's "Multi-Threaded Rendering" (`decoupledPresent` in `~/.lunarclient/settings/game/<profile>/performance.json`)
  acquires/presents the swapchain itself; the mod detects it and keeps the game's output (no FG). Must be off
  (`{"version":38,"decoupledPresent":false}`). Presenting from both swapchains stalls in fullscreen.
- Since 0.7.2 `LunarCompat` (mixin config plugin of iris-dlss, `acceptTargets` = after all configs are prepared,
  before any mixin applies) does both Lunar fixes itself: sets `decoupledPresent=false` in the active profile
  (`profile_manager.json` `active`; Lunar reads the file ~10 s later, after `[LC] Starting Lunar client`) and
  removes the @Redirect from Vitrail's `VulkanCommandEncoderMixin.vitrail$afterPass` class node (MixinTransformer
  .processor -> configs -> MixinInfo.state.classNode, same fields in Lunar's Mixin 0.8.x in genesis.jar) when another
  mixin redirects the barrier or Lunar is detected (`mixins.ichor.*` configs / game dir in `.lunarclient`).
  `lunarCompat=false` turns it off. Dev test: `-Diris-dlss.forceLunarCompat=true -Diris-dlss.lunarDir=<fake dir>`
  via JAVA_TOOL_OPTIONS, `-Dmixin.debug.export=true` and check `run/.mixin.out/.../VulkanCommandEncoder.class`.
  Verified in dev and in Lunar 2026-10-30 (fresh settings profile `DLSS`: performance.json created with
  `decoupledPresent:false`, no Vitrail crash with the plain jar, presenter output, FG x2, Solas draws). Lunar
  drops INFO lines logged this early, so LunarCompat's messages don't show in Lunar's log.
- Lunar test profile `dlss-test` (launcher profile, isolated, 26.3 Fabric): only Fabric API, Sodium 0.9.2 (added by
  Lunar's Auto Fix), Vitrail (plain), iris-dlss (latest), Solas + `vitrail/pack.txt`, world `vulkan-test`. surv: see "Current Lunar install".
  Tested there 2026-10-30: no crash, no VSync FG x2 ~365/185 fps without shaders, Solas 140/72 (1280x720 window).
  surv keeps shaders off (`enabled=false`); its Vitrail toggle key is Insert (SendKeys can't send it, use
  keybd_event VK 0x2D scan 0x52 extended). Screen captures (CopyFromScreen) of the game are black without VSync
  (independent flip); take in-game F2 screenshots instead. Launcher can't be driven by CDP (main window hidden); clicking via
  user32 SetCursorPos/mouse_event + CopyFromScreen screenshots works.
- The user's Solas settings: `shaderpacks/Solas Shader V3.8.zip.txt` there (DOF, VL, tonemap tweaks).
- Lunar history: 2026-10-27 switch to Vitrail (Solas `BLOOM=true` there: with DOF on Vitrail can't compile
  composite14 otherwise; voxy jars renamed `.disabled`; old files in `mods-backup-2026-10-27-vitrail/`). The first
  start crashed (Lunar's `lunar$maybeBarrier` and Vitrail's `vitrail$afterPass` both @Redirect `memoryBarrier` in
  `VulkanCommandEncoder.submitRenderPass`); until 0.7.2 that was worked around with `tools/PatchVitrail.java` and a
  local `-lunarfix` Vitrail jar (`../vitrail-dl`, never publish), since 0.7.2 `LunarCompat` does it at runtime.
  Could be reported to Vitrail (use @WrapOperation instead of @Redirect); ask the user first.
- Current Lunar install (2026-10-30): surv and dlss-test have `iris-dlss-0.7.3+mc26.3.jar` (0.7.2 in `mods-backup-2026-10-30-073/`) + plain `vitrail-0.13.0-beta+mc26.3.jar`,
  `diagnosticRecording=false`; Lunar's active settings profile is `Default` (`DLSS` = empty test profile).

## Build & test

- Gradle needs **JDK 25**: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`. Use `--no-daemon`; "Could not connect to the Gradle
  daemon" happens often, `gradlew --stop` and rerun.
- Bridge: `dlss-bridge/fetch-deps.ps1`, `dlss-bridge/build.bat` (MSVC 2022, Build Tools are enough). Mod:
  `cd mod && gradlew build` -> `mod/build/libs/iris-dlss-*.jar`. voxy-vulkan: `cd voxy-vulkan && gradlew build`.
- Test loop: `cd mod && gradlew runClient -PtestWorld=vulkan-test` joins/creates the world, screenshot
  `mod/run/screenshots/dlss-test.png` after 200 ticks, quits (`DevTest`, `DevTestWorldMixin`). Options:
  `-PtestCommands="time set 1500;weather clear;tp @p -36 84 18 -90 20"` (tree/shadow view; `tp @p -36 82 18 180 8`
  = beach view), `-PtestPitch`, `-PtestTicks=600` (more for screenshots after loading), `-PwindowSize=1920x1080`,
  `-PdlssBenchmark` (logs `Iris DLSS benchmark:` lines), `-PdlssOff`, `-PdlssDebug=spin|walk` (FG capture into
  `mod/run/iris-dlss/captures`, only fires with Frame Generation). Look at the screenshot.
- Dev `options.txt`: `fullscreen:false` (else a focus change minimizes and caps at 10 fps),
  `inactivityFpsLimit:"minimized"` (else the 30 fps AFK limit hits benchmarks). Run benchmarks with nothing else
  loading the PC (±25 %). Logs written across midnight get lost (log roll), rerun.
- Voxy dev test: move `mod/run/mods-voxy/*` (Voxy) plus voxy-vulkan's jar into `mod/run/mods`, no shader pack,
  one run at render distance 32 so Voxy ingests, then lower it. `-PtestTicks=400+` (meshing takes seconds).
- OpenGL reference shot: `enabled=false` in `config/iris-dlss.properties` + `preferredGraphicsBackend:"opengl"` in
  `options.txt` (Vitrail: `vitrail/graphics-api.txt`), then restore.

## DLSS notes

- Two main targets swapped in `GameRendererMixin`: world into a DLSS-sized one, GUI into a window-sized one.
  Jitter in `CameraMixin` -> `DlssUpscaler.applyJitter`; world depth copied before the hand pass; motion vectors
  from depth: camera plus moving entities (`EntityMotion`: positions from `LevelExtractorMixin`, nearest 32 moving
  ones as boxes in the motion shader's UBO, translation + body yaw). Depth is reverse-Z (`DepthConvention`).
  FG matrices are built in double precision (Matrix4d); F5 perspective change resets history. Measured
  2026-10-30 (spin capture, horizontal shift of generated vs real frames): generated frames land at 44-56 % between real ones.
- Pack noise: Solas/BSL/Complementary animate their dither only `#ifdef TAA`; with TAA off it was a fixed screen
  pattern that crawled under DLSS (user: "edges crawl standing still"). `VitrailDitherMixin` (IncludeExpander
  .logicalLines) + `ShaderPackTweaks.animateDither` turn on just those blocks (<= 4 statements using
  frameCounter/frameTimeCounter, no gl_Position/Jitter). User confirmed the jitter gone. Mipmaps / LOD bias
  barely changed the still-frame difference afterwards (0.27-0.29 at 1080p).
- `MemoryCheck`: toast when max heap < 4 GB or heap >= 90 % for 3 checks 10 s apart.
- Dev: `-PdlssDebug=still`, `-PcaptureAt=N`, `-PtestRepeat="cmd;cmd"` (every tick), JAVA_TOOL_OPTIONS
  `-Diris-dlss.captureFrames=12`. Dev `options.txt` has `pauseOnLostFocus:false` (else the pause menu stops FG and
  the capture). Kill old test mobs first (`kill @e[type=!player]`), they persist in `vulkan-test`.
- Benchmark costs (4060 Ti, 1080p): DLSS SR preset K ~2 ms, FG ~3 ms per rendered frame; E ~1 ms cheaper than K,
  L ~1.8 ms more, M ~0.9 ms more at Performance. Mode Custom (33-100 %) uses the closest NGX mode. Render-thread
  time goes to waiting in RenderPearl's `submit` (GPU-bound) and the presenter's `frameStart`.
- Settings UI: Sodium pages "Settings"/"Advanced" (`SodiumOptions`) and Mod Menu screen (`DlssSettingsScreen`);
  overlay in the HUD (`HudMixin`). Screenshot them with `-PdlssScreen=mod|sodium|sodium2` (`DevScreens`).
- Multi frame generation: 2x/3x/4x (`frameGenMultiplier`), limited to the GPU's `DLSSG.MultiFrameCountMax`
  (4060 Ti: 2x only). One DLSS-G evaluate per generated frame. Frames are paced evenly over the game's frame
  interval with and without VSync. 3x/4x verified in a friend's log (RTX 50, Lunar).
- Hardware: FG needs RTX 40/50; on the RTX 3070 dev machine FG fails with
  `NVSDK_NGX_Result_FAIL_UnableToInitializeFeature` (expected). `dlss-bridge/out/dlss_probe.exe` reports SR/FG.

## Releases

`"C:\Program Files\GitHub CLI\gh.exe" release create <tag> <jars> -R laurensanp/iris-dlss --title "Iris DLSS Bridge
<version> (Minecraft 26.3)" --latest` (gh is logged in as laurensanp; up to 0.7.1 they were `--prerelease`). v0.7.0 (2026-10-27, first with Vitrail): `iris-dlss-0.7.0+mc26.3.jar` +
`voxy-vulkan-0.1.1+mc26.3.jar`; Vitrail is never attached (link to Modrinth + `tools/PatchVitrail.java` for Lunar).
v0.7.1 (2026-10-27): pacing without VSync, vertex buffer reuse, diagnosticRecording (same voxy-vulkan 0.1.1
attached). v0.7.2 (2026-10-30): LunarCompat (plain Vitrail jar in Lunar, threaded present off); full release marked "Latest" (user asked). v0.7.3 (2026-10-30, Latest): `lunarCompat`
kept in `iris-dlss.properties` (0.7.2's `DlssConfig.save` dropped the key, so `lunarCompat=false` held one start). v0.5.0/v0.6.0 notes start with an "Outdated, needs the archived fork" line. Next release: bump `version` in
`mod/build.gradle` (and `voxy-vulkan/build.gradle` if it changed), README notes, keep the asset names.

## Working with the user

- Verify every change with screenshots/frame captures as above.
- Ask before pushing (memory: verified work may be committed and pushed right away), and before downloading
  third-party code (shader packs too: Solas, Complementary are free to download but not ours to redistribute).
