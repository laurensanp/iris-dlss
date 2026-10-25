# Iris DLSS Bridge (Minecraft 26.3, Fabric)

NVIDIA **DLSS Super Resolution** and **DLSS Frame Generation** for Minecraft 26.3, running inside the
game's own Vulkan renderer, together with **Iris shader packs** on Vulkan (through the
[iris-vulkan](https://github.com/laurensanp/iris-vulkan) fork of Iris).

Status: **experimental**, personal project, Windows + NVIDIA only. Tested on an RTX 4060 Ti (driver 617,
Windows 11) with Solas 3.8. Bug reports welcome in the [issues](https://github.com/laurensanp/iris-dlss/issues).

## Contents

* [Requirements](#requirements)
* [Install (players)](#install-players)
* [Lunar Client](#lunar-client)
* [Settings](#settings)
* [Shader packs on Vulkan](#shader-packs-on-vulkan)
* [Performance](#performance)
* [Troubleshooting](#troubleshooting)
* [Build from source (developers)](#build-from-source-developers)
* [How it works](#how-it-works)
* [License](#license)

## Requirements

| | |
|---|---|
| OS | Windows 10 / 11, 64-bit |
| GPU | NVIDIA RTX. Super Resolution: RTX 20 or newer. **Frame Generation: RTX 40 / 50 only.** Recent driver. |
| Game | Minecraft **26.3** with Fabric Loader **0.20.1+** |
| Mods | [Sodium](https://modrinth.com/mod/sodium) 0.9.2+ for 26.3 (needed for shaders), the **Iris Vulkan fork** (below) for shader packs |
| Not together with | ResolutionScaler / GGTU or other mods that change the render resolution; regular Iris (use the fork instead) |

## Install (players)

1. Install Fabric Loader for Minecraft 26.3.
2. Download from the [Releases](https://github.com/laurensanp/iris-dlss/releases) page:
   * `iris-dlss-<version>+mc26.3.jar` (this mod; NVIDIA's DLSS files are inside)
   * `iris-fabric-vulkan+mc26.3.jar` (Iris for Vulkan, from
     [iris-vulkan releases](https://github.com/laurensanp/iris-vulkan/releases)), only if you want shader packs
3. Put both jars and [Sodium](https://modrinth.com/mod/sodium) into `.minecraft/mods/`. Remove a regular
   Iris jar if you have one.
4. Put a shader pack (zip) into `.minecraft/shaderpacks/` and pick it in Video Settings → Shader Packs.
   [Solas](https://modrinth.com/shader/solas-shader) is the best tested one.
5. Start the game. It starts on **Vulkan** while DLSS is on (the "Graphics API" option is overridden).

Updating: close the game, replace the jars, start again. Your settings stay in
`config/iris-dlss.properties`.

## Lunar Client

Works in Lunar Client profiles that run Fabric for 26.3 (put the jars into the profile's `mods`
folder while the game is closed).

**Turn off Lunar's "Multi-Threaded Rendering"**, otherwise Frame Generation never starts: Lunar then
acquires and presents the game's frames on its own threads, and the DLSS presenter cannot take over
(the DLSS settings show "turn off Lunar's Multi-Threaded Rendering"). In game: Right Shift → Settings →
search "Multi-Threaded". Or, with Lunar's game closed, set it in
`%USERPROFILE%\.lunarclient\settings\game\<profile>\performance.json`:

```json
{"version":38,"decoupledPresent":false}
```

(keep the `version` your file already has). Lunar's log then shows `[LC] Disabled threaded present`.

## Settings

Video Settings → **DLSS** (Sodium's settings screen, two pages), or Mod Menu → Iris DLSS Bridge. Changes
apply immediately.

| Setting | What it does |
|---|---|
| DLSS | On / off. Off renders natively (still on Vulkan until the next start). |
| Mode | DLAA (100 %), Quality (67 %), Balanced (58 %), Performance (50 %), Ultra Performance (33 %), **Custom** |
| Render resolution | Only for Custom: 33 – 100 % per axis |
| Model | K (transformer, best detail, default), L / M (second-generation transformer, NVIDIA's defaults for Ultra Performance / Performance, more expensive), J (less ghosting), E (CNN, cheapest), driver default |
| Sharpening | Contrast-adaptive sharpening after DLSS, 0 – 100 % |
| Frame Generation | One generated frame between two rendered ones (RTX 40/50). Needs the presenter. |
| DLSS presenter | Shows frames through its own swapchain (needed for Frame Generation, paces frames for low latency) |
| Performance overlay | Off / FPS / Detailed, in the top left corner |
| Advanced: Texture LOD bias | Sharper distant textures at lower render resolutions (after a restart) |
| Advanced: Turn off shader pack TAA | Switches the pack's own TAA off at start (it blurs on top of DLSS) |
| Advanced: Hand distance, Jitter direction | Troubleshooting |

F3 also shows two DLSS lines: render size → output size, and shown / rendered fps, pacing, latency.

## Shader packs on Vulkan

Iris normally needs OpenGL. The [iris-vulkan](https://github.com/laurensanp/iris-vulkan) fork runs packs
on Minecraft's Vulkan renderer (branch `vulkan-m2`). State on 2026-10-26:

| Pack | Status |
|---|---|
| Solas 3.8 | Runs (best tested) |
| Complementary Reimagined / Unbound r5.9.3 | Run |
| BSL 10.1.8 | Runs |
| MakeUp Ultra Fast 9.6 | Runs |
| Photon 1.4, Bliss 2.1.2, Sildur's Vibrant 2.02 | Don't compile yet (the game falls back to no shaders, or the game's shaders for single programs) |
| Rethinking Voxels | Needs compute shaders / SSBOs / custom images: not possible yet |

Missing on Vulkan for every pack (planned, "M3"): **shadows** (shadow pass), compute shaders, custom /
3D images (voxel and flood-fill lighting read zero), geometry and tessellation shaders. Pack options for
those have no effect yet. Voxy is not supported on Vulkan yet.

## Performance

Solas 3.8, 1920×1080, render distance 32, RTX 4060 Ti, fps rendered (shown with Frame Generation):

| DLSS off | DLAA | Quality | Performance | Quality + FG |
|---|---|---|---|---|
| ~100 | ~80 | ~140 | ~145 | ~90 (~180 shown) |

The game is GPU-bound here; DLSS itself costs ~2 ms per frame at 1080p with model K (E is ~1 ms
cheaper, L ~1.8 ms more), Frame Generation ~3 ms per rendered frame. A lower render distance helps a lot.
Numbers vary with the scene and with whatever else runs on the PC.

## Troubleshooting

* **"DLSS: unavailable (…)"** in the settings or on F3: the reason is in brackets; full details in
  `logs/latest.log` (search for `Iris DLSS`).
* **Frame Generation stays off / "Presenter starting"**: Lunar's Multi-Threaded Rendering (see above), or
  an RTX 20/30 card (`NVSDK_NGX_Result_FAIL_UnableToInitializeFeature` in the log is expected there).
* **Shader pack doesn't load**: the log says `Failed to create shader rendering pipeline`; lines with
  `Not supported on Vulkan yet` list what the pack uses that the port can't do yet.
* **Back to OpenGL** (e.g. to compare): set `enabled=false` in `config/iris-dlss.properties` and
  `preferredGraphicsBackend:"opengl"` in `options.txt`.
* Settings file reference: `config/iris-dlss.properties` (commented, written on every start).

## Build from source (developers)

Requirements: Windows 10/11, NVIDIA RTX GPU, Visual Studio 2022 or its Build Tools with the C++
workload, **JDK 25** (Gradle needs it in `JAVA_HOME`), git.

```bat
git clone --recursive https://github.com/laurensanp/iris-dlss.git
cd iris-dlss\dlss-bridge
powershell -ExecutionPolicy Bypass -File fetch-deps.ps1   :: NVIDIA DLSS SDK + Vulkan headers (from GitHub)
set JAVA_HOME=C:\path\to\jdk-25
build.bat                                                 :: out\irisdlss.dll, out\dlss_probe.exe
out\dlss_probe.exe                                        :: optional: reports SR / FG support without the game
```

The mod compiles against Sodium's config API: download
[sodium-fabric-0.9.2+mc26.3.jar](https://modrinth.com/mod/sodium/version/mc26.3-0.9.2-fabric) into
`libs/` (or set `sodium_jar` in `mod/gradle.properties`).

```bat
cd ..\mod
gradlew build                                             :: build\libs\iris-dlss-*.jar (natives included)
```

The Iris fork is the `iris-vulkan` submodule (or clone it next to this repo):

```bat
cd ..\iris-vulkan
gradlew :fabric:build                                     :: build\libs\iris-fabric-*.jar (repository root)
```

### Test loop (in the fork)

Copy `mod\build\libs\iris-dlss-*.jar` into `iris-vulkan\fabric\run\mods\`, then from `iris-vulkan`:

```bat
gradlew :fabric:runClient -PtestWorld=vulkan-test
```

joins (first time: creates) a test world, saves `fabric/run/screenshots/vulkan-test.png` after 200 ticks
and quits. Options: `-PtestCommands="time set 6000;tp @p -36 82 18 180 8"` (chat commands after joining),
`-PtestPitch=40`, `-PtestTicks=900`, `-PwindowSize=1920x1080`, `-PdlssBenchmark` (cycles DLSS modes, logs
fps as `Iris DLSS benchmark:`), `-PdlssScreen=mod|sodium|sodium2` (opens the settings for the screenshot),
`-PjfrProfile` (Java Flight Recording), `-PdlssForeignAcquire` (simulates Lunar's swapchain handling).
Pick the pack with `shaderPack=` in `fabric/run/config/iris.properties`. Use `fullscreen:false` in
`fabric/run/options.txt` (a minimized fullscreen window is capped at 10 fps).

More background for contributors: [`CLAUDE.md`](CLAUDE.md) (status, design, test notes) and
[`docs/vulkan-port.md`](docs/vulkan-port.md) (plan and milestones of the Iris port).

## How it works

```
Minecraft (RenderPearl Vulkan backend)                 dlss-bridge (irisdlss.dll, NGX)
──────────────────────────────────────                 ────────────────────────────────
vkCreateInstance / vkCreateDevice ───────────────────▶ adds NGX + DLSS-G extensions, features,
                                                        a presenter queue, an optical-flow queue
world → low-res main target (jittered projection)
  (Iris on Vulkan: gbuffers, deferred, composite, final)
motion-vector pass (RenderPearl pipeline)
game command buffer ◀──── DLSS SR recorded here ────── on the game's color / depth images
sharpen pass → full-res target, GUI on top
game command buffer ◀──── frame copy + DLSS-G ───────  generated frame
submit signals a timeline semaphore ─────────────────▶ presenter thread (own queue): generated
                                                        frame, then the real one half a frame
                                                        later, swapchain on a child window
```

* The game's swapchain image is not acquired while the presenter runs; if it declines a frame, the
  game presents normally.
* RenderPearl keeps every image in GENERAL and stores them bottom row first (like OpenGL); the
  bridge transitions images around NGX calls and flips when it blits to its swapchain.
* Frame pacing: with VSync the presenter waits (`VK_KHR_present_wait`) until the generated frame is
  on screen before showing the real one. Like NVIDIA Reflex, the game starts each frame only when the
  presenter is about to need it.
* The hand pass clears the main depth buffer, so the world's depth is copied before it; motion
  vectors use it, the hand gets zero motion.
* Motion vectors are reconstructed from depth and the camera only (no per-object motion yet).
* Depth convention: vanilla 26.3 is reverse-Z (`DepthConvention`).

| Path | What |
|------|------|
| `dlss-bridge/src/bridge.cpp` | device-creation patching, NGX init, DLSS SR, presenter + DLSS-G |
| `dlss-bridge/src/jni.cpp` | JNI for `com.irisdlss.DlssNative` |
| `dlss-bridge/src/probe.cpp` | `dlss_probe.exe`: a tiny Vulkan "game" running SR, FG and the presenter |
| `mod/.../DlssUpscaler.java` | jitter, motion-vector + sharpen pipelines, SR call, FG inputs |
| `mod/.../VulkanPresenter.java` | hands the finished frame to the bridge's presenter |
| `mod/.../SodiumOptions.java`, `DlssSettingsScreen.java` | settings (Sodium pages, Mod Menu screen) |
| `mod/.../mixin/` | Vulkan instance/device, command encoder, surface, samplers, backend choice, GameRenderer, Camera, HUD overlay, F3 |
| `iris-vulkan/` | the Iris fork (submodule), its Vulkan code in `common/src/main/java/net/irisshaders/iris/vulkan` |

## Known issues

* No shadows with shader packs yet (shadow pass not ported).
* Some blur / noise in the distance while moving: motion vectors are camera-only (moving entities,
  water have none).
* Frame Generation matrices are a best effort.
* Only Windows + NVIDIA.

## License

This mod: [MIT](LICENSE). The Iris fork: LGPL-3.0, like Iris. NVIDIA's DLSS runtime files in the jar
(`nvngx_dlss.dll`, `nvngx_dlssg.dll`) are redistributed under the NVIDIA DLSS SDK license
(`NVIDIA_DLSS_LICENSE.txt` in the jar); `fetch-deps.ps1` downloads the SDK, it is not part of this
repository. Shader packs are not included; get them from their authors.
