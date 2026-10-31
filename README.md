<div align="center">

# PYRAXIS

**Redefining Minecraft Rendering.**

![Vulkan](https://img.shields.io/badge/Vulkan-AC162C?logo=vulkan&logoColor=white)
![NVIDIA DLSS](https://img.shields.io/badge/NVIDIA-DLSS-76B900?logo=nvidia&logoColor=white)
![Frame Generation](https://img.shields.io/badge/Frame-Generation-76B900?logo=nvidia&logoColor=white)
![Vitrail](https://img.shields.io/badge/Vitrail-Shaders-7B61A8)

</div>

---

NVIDIA **DLSS Super Resolution** and **DLSS Frame Generation** for Minecraft 26.3, running inside the
game's own Vulkan renderer, together with **shader packs on Vulkan** through
[Vitrail](https://modrinth.com/mod/vitrail-shaders).

Status: **experimental**, personal project, Windows + NVIDIA only. Tested on an RTX 4060 Ti (driver 617,
Windows 11) with Solas 3.8. Bug reports welcome in the [issues](https://github.com/laurensanp/pyraxis-render/issues).

The project was called *Iris DLSS Bridge*; jar, config file and log lines keep the old `iris-dlss` name so
existing installs keep working. Until 0.6.0 shader packs ran through an own Vulkan port of Iris
([iris-vulkan](https://github.com/laurensanp/iris-vulkan), archived); Vitrail replaces it.

## Contents

* [What you need](#what-you-need)
* [Install (players)](#install-players)
* [Lunar Client](#lunar-client)
* [Settings](#settings)
* [Shader packs (Vitrail)](#shader-packs-vitrail)
* [Voxy LODs on Vulkan (paused)](#voxy-lods-on-vulkan-paused)
* [Performance](#performance)
* [Troubleshooting](#troubleshooting)
* [Build from source (developers)](#build-from-source-developers)
* [How it works](#how-it-works)
* [Known issues](#known-issues)
* [License](#license)

## What you need

| | |
|---|---|
| OS | Windows 10 / 11, 64-bit |
| GPU | NVIDIA RTX. Super Resolution: RTX 20 or newer. **Frame Generation: RTX 40 / 50 only.** Recent driver. |
| Game | Minecraft **26.3** with Fabric Loader **0.20.1+** |
| Not together with | ResolutionScaler / GGTU or other mods that change the render resolution; the old iris-vulkan fork |

Mods, all in `.minecraft/mods/` (or the Lunar profile's `mods/`):

| Mod | Where from | Needed for |
|---|---|---|
| `iris-dlss-<version>+mc26.3.jar` (this mod, NVIDIA's DLSS files inside) | [Releases](https://github.com/laurensanp/pyraxis-render/releases) | DLSS SR + FG |
| [Sodium](https://modrinth.com/mod/sodium) 0.9.x for 26.3 | Modrinth | the settings pages, Vitrail |
| [Vitrail](https://modrinth.com/mod/vitrail-shaders) for 26.3 (tested: 0.13.0-beta) | Modrinth | shader packs on Vulkan |
| [Fabric API](https://modrinth.com/mod/fabric-api) | Modrinth | Vitrail |
| [Mod Menu](https://modrinth.com/mod/modmenu) | Modrinth | optional, second settings screen |
| a shader pack, e.g. [Solas](https://modrinth.com/shader/solas-shader) 3.8 | Modrinth | goes into `shaderpacks/` |

Without Vitrail the mod still runs DLSS on the vanilla picture. According to Vitrail, regular Iris can stay
installed next to it (Iris draws on OpenGL, Vitrail on Vulkan; this mod starts the game on Vulkan). Not
tested here.

## Install (players)

1. Install Fabric Loader for Minecraft 26.3.
2. Put the mods from the table above into `mods/`. Remove the old `iris-fabric-vulkan+mc26.3.jar` if you
   have it.
3. Put a shader pack (zip) into `shaderpacks/`.
4. Start the game. It starts on **Vulkan** while DLSS is on (the "Graphics API" option is overridden).
5. Press **`I`** for Vitrail's shader pack screen and pick the pack (`K` toggles shaders, `R` reloads).
   Vitrail remembers it in `vitrail/pack.txt`.

Updating: close the game, replace the jars, start again. Your settings stay in
`config/iris-dlss.properties`; the pack's settings in `shaderpacks/<pack>.txt`.

## Lunar Client

Works in Lunar Client profiles that run Fabric for 26.3 (put the jars into the profile's `mods`
folder while the game is closed; Lunar ships its own Sodium, Fabric API and Mod Menu, keep those).

Since 0.7.2 the mod handles Lunar's conflicts itself when the game starts (nothing to set up, also in a
new Lunar profile); `lunarCompat=false` in `config/iris-dlss.properties` turns that off:

* **Lunar's "Multi-Threaded Rendering" is turned off** in the active settings profile
  (`%USERPROFILE%\.lunarclient\settings\game\<profile>\performance.json`, `"decoupledPresent":false`) before
  Lunar reads it. With it on, Lunar acquires and presents the game's frames on its own threads and Frame
  Generation cannot start (the DLSS settings then show "turn off Lunar's Multi-Threaded Rendering"). If you
  turn it back on in game, it is off again after the next start.
* **Lunar's Turbo Entity / Turbo Block Entity Mode is turned off** (same file, `"turboEntityMode":false`,
  `"turboBlockEntityMode":false`, since 0.7.7, only with Vitrail installed). Lunar switches them on remotely
  and doesn't show them in its settings; they bake resting entities and block entities into Lunar's own
  pipelines, which the shader pack doesn't draw: armor stands vanished after a moment and sign text was
  missing with shaders on.
* **Vitrail no longer crashes Lunar at start** (`Critical injection failure: Redirector lunar$maybeBarrier ...
  VulkanCommandEncoder`): Lunar and [Vitrail](https://github.com/avpbynf/Vitrail-Shaders) (LGPL-3.0, by its
  authors, not part of this mod) both redirect the same barrier call in RenderPearl's command encoder. Vitrail's
  redirect only narrows the barrier after its own passes, so the mod drops that one redirect while the game
  starts (only when another mod redirects the same call, i.e. in Lunar); the game's full barrier stays. Use the
  normal Vitrail jar from Modrinth. Tested with Vitrail 0.13.0-beta.

**FPS drop after a few minutes that recovers later**: give the game more memory in Lunar's launcher
(Settings → Allocated Memory, e.g. 8 GB). With 3 GB the Java heap fills up with shader packs and maps and the
game keeps stalling for garbage collection (Lunar's log shows many `Pause - Unknown` lines). The mod shows a
notice in game when Java has less than 4 GB, or when its memory stays nearly full for half a minute.

## Settings

Video Settings → **DLSS** (Sodium's settings screen, two pages), or Mod Menu → PYRAXIS. Changes
apply immediately.

| Setting | What it does |
|---|---|
| DLSS | On / off. Off renders natively (still on Vulkan until the next start). |
| Mode | DLAA (100 %), Quality (67 %), Balanced (58 %), Performance (50 %), Ultra Performance (33 %), **Custom** |
| Render resolution | Only for Custom: 33 – 100 % per axis |
| Model | K (transformer, best detail, default), L / M (second-generation transformer, NVIDIA's defaults for Ultra Performance / Performance, more expensive), J (less ghosting), E (CNN, cheapest), driver default |
| Sharpening | Robust contrast-adaptive sharpening (AMD FSR 1 RCAS) after DLSS, 0 – 100 %; holds back on fine repeating noise, so no grid on leaves or sand |
| Frame Generation | Generated frames between rendered ones (RTX 40/50). Needs the presenter. Best with VSync and at least ~60 fps rendered without it. |
| Generated frames | 2x, 3x, 4x. Only what the GPU supports is offered: 3x / 4x (DLSS 4 multi frame generation) need an RTX 50; on RTX 40 the option shows 2x and is greyed out. |
| DLSS presenter | Shows frames through its own swapchain (needed for Frame Generation, paces frames for low latency) |
| Performance overlay | Off / FPS / Detailed, in the top left corner: fps shown and rendered, each with its 1 % low (last five seconds) |
| Advanced: Texture LOD bias | Sharper distant textures at lower render resolutions (after a restart) |
| Advanced: Turn off shader pack TAA | Switches the pack's own TAA off (it blurs and jitters on top of DLSS), also for a pack that is switched on later in game. With Vitrail, the pack's noise (shadows, AO, volumetric light, reflections) still changes every frame as with its TAA, so DLSS smooths it instead of showing a fixed, crawling pattern |
| Advanced: Hand distance, Jitter direction | Troubleshooting |

F3 also shows two DLSS lines: render size → output size, and shown / rendered fps, pacing, latency.

## Shader packs (Vitrail)

[Vitrail](https://github.com/avpbynf/Vitrail-Shaders) (LGPL-3.0, by avpbynf) runs OptiFine-format shader
packs on Minecraft's Vulkan renderer. It is a separate mod, not part of this repository. It works with
DLSS as it is: it draws the world into the game's main render target (reverse-Z depth kept) with the
projection the game draws with, so the pack gets DLSS' jittered camera, and its composite/final passes run
before the upscale. Tested 2026-10-27 with Vitrail 0.13.0-beta and Solas 3.8: SR and Frame Generation
work, motion vectors and the hand are right.

What this mod adds for Vitrail:

* Vitrail's own **Render Scale** (FSR 1) is ignored while DLSS is on (DLSS already sets the render size).
* The pack's TAA is switched off (`disablePackTaa`, reads `vitrail/pack.txt` and the pack's
  `shaderpacks/<pack>.txt`, which Vitrail shares with Iris).

Known with Solas 3.8 on Vitrail 0.13.0:

* `SSAO=false`, or `DOF=true` together with `BLOOM=false`, make a program fail to compile, and Vitrail then
  draws nothing of the pack (Solas uses undeclared uniforms in code that Iris removes as unused, Vitrail
  doesn't). The log says `<program> did not compile, nothing of this pack will be drawn`.
* At night the first-person hand is lit too brightly.

Vitrail's own compatibility table: [docs/compatibility.md](https://github.com/avpbynf/Vitrail-Shaders/blob/main/docs/compatibility.md).

## Voxy LODs on Vulkan (paused)

[Voxy](https://modrinth.com/mod/voxy) only renders through OpenGL and refuses the Vulkan backend.
[`voxy-vulkan/`](voxy-vulkan) is a separate small mod that makes it work on Vulkan without changing Voxy:
Voxy keeps its storage, LOD import ("ingest") and world engine, its OpenGL renderer is never started, and
voxy-vulkan draws the LOD sections with an own Vulkan renderer (sections picked by distance, meshed off the
render thread, block texture colours with biome tint, fog pushed out to the LOD distance).

* **Without a shader pack: works** (tested 2026-10-27 in a dev world and in Lunar Client).
* **With a shader pack (Vitrail): LODs are switched off.** Drawing them would need the pack's own
  programs; this work is paused. Vitrail supports Distant Horizons instead.
* LODs cast no shadows.

Install: Voxy 0.2.20-beta (Modrinth, needs Fabric API) and `voxy-vulkan-*.jar` (attached to the
[releases](https://github.com/laurensanp/pyraxis-render/releases), experimental) in `mods/`. Voxy is All-Rights-Reserved: voxy-vulkan contains none of its code and
is only built against it.

## Performance

Solas 3.8, 1920×1080, render distance 8, static view, RTX 4060 Ti, fps rendered (shown with Frame
Generation), 2026-10-27:

| DLSS off | DLAA | Quality | Performance | Quality + FG |
|---|---|---|---|---|
| ~146 | ~123 | ~176 | ~217 | ~122 (~245 shown) |

That is 5–10 % more than the old Iris fork in the same scene. The game is GPU-bound here; DLSS itself costs
~2 ms per frame at 1080p with model K (E is ~1 ms cheaper, L ~1.8 ms more), Frame Generation ~3 ms per
rendered frame. A lower render distance helps a lot. Numbers vary with the scene and with whatever else
runs on the PC.

## Troubleshooting

* **"DLSS: unavailable (…)"** in the settings or on F3: the reason is in brackets; full details in
  `logs/latest.log` (search for `Iris DLSS`).
* **Frame Generation stays off / "Presenter starting"**: Lunar's Multi-Threaded Rendering was turned on during
  this session (it is off again after a restart, see [Lunar Client](#lunar-client)), or
  an RTX 20/30 card (`NVSDK_NGX_Result_FAIL_UnableToInitializeFeature` in the log is expected there).
* **Shader pack doesn't show**: search the log for `(Vitrail)` lines with `did not compile`; see the
  Solas notes above. Vitrail's shader screen (`I`) also shows load errors.
* **Back to OpenGL** (e.g. to compare): set `enabled=false` in `config/iris-dlss.properties` and
  `preferredGraphicsBackend:"opengl"` in `options.txt` (Vitrail additionally keeps its own choice in
  `vitrail/graphics-api.txt`).
* **Finding frame drops**: set `diagnosticRecording=true` in `config/iris-dlss.properties` (game closed), play
  until it drops, quit normally. A Java Flight Recording of the last 10 minutes lands in
  `iris-dlss/recordings/fps-<time>.jfr` (open it with JDK Mission Control or `jfr print`); attach it to an issue.
  Set it back to `false` afterwards. Works in Lunar too, which has no JVM argument field.
* Settings file reference: `config/iris-dlss.properties` (commented, written on every start).

## Build from source (developers)

Everything that is ours is in this repository. What is not ours is downloaded, never committed:

| Item | Where it goes | Source |
|---|---|---|
| NVIDIA DLSS SDK, Vulkan headers | `dlss-bridge/third_party/` | `dlss-bridge/fetch-deps.ps1` (GitHub) |
| Sodium 0.9.2 jar (compile only) | `libs/sodium-fabric-0.9.2+mc26.3.jar` | [Modrinth](https://modrinth.com/mod/sodium/version/mc26.3-0.9.2-fabric) |
| Voxy 0.2.20-beta jar (compile only, voxy-vulkan) | `libs/voxy-0.2.20-beta.jar` | [Modrinth](https://modrinth.com/mod/voxy) |
| Sodium, Fabric API, Vitrail jars (test runs) | `mod/run/mods/` | Modrinth |
| Shader packs (test runs) | `mod/run/shaderpacks/` | Modrinth (Solas, Complementary, ...) |

Requirements: Windows 10/11, NVIDIA RTX GPU, Visual Studio 2022 or its Build Tools with the C++
workload, **JDK 25** (Gradle needs it in `JAVA_HOME`), git.

```bat
git clone https://github.com/laurensanp/pyraxis-render.git
cd pyraxis-render\dlss-bridge
powershell -ExecutionPolicy Bypass -File fetch-deps.ps1   :: NVIDIA DLSS SDK + Vulkan headers (from GitHub)
set JAVA_HOME=C:\path\to\jdk-25
build.bat                                                 :: out\irisdlss.dll, out\dlss_probe.exe
out\dlss_probe.exe                                        :: optional: reports SR / FG support without the game
cd ..\mod
gradlew build                                             :: build\libs\iris-dlss-*.jar (natives included)
cd ..\voxy-vulkan
gradlew build                                             :: build\libs\voxy-vulkan-*.jar (optional)
```

### Test loop

`mod/run` is the test game directory (not in git). Set it up once:

* `mod/run/mods/`: Sodium, Fabric API, Vitrail (the mod itself comes from the build).
* `mod/run/shaderpacks/`: a pack (Solas), plus the own test packs from [`testpacks/`](testpacks).
* `mod/run/vitrail/pack.txt`: `pack=Solas Shader V3.8.zip` and `enabled=true` (or pick it with `I` once).
* `mod/run/options.txt`: `fullscreen:false` (a minimized fullscreen window is capped at 10 fps) and
  `inactivityFpsLimit:"minimized"` (else the AFK limit of 30 fps hits long runs).

Then, from `mod`:

```bat
gradlew runClient -PtestWorld=vulkan-test
```

joins (first time: creates, fixed seed) a test world, saves `run/screenshots/dlss-test.png` after 200
ticks and quits. Options: `-PtestCommands="time set 1500;weather clear;tp @p -36 84 18 -90 20"` (chat
commands after joining), `-PtestPitch=40`, `-PtestTicks=900`, `-PwindowSize=1920x1080`, `-PdlssBenchmark`
(cycles DLSS modes, logs fps as `Iris DLSS benchmark:`), `-PdlssOff` (DLSS off, still on Vulkan),
`-PdlssScreen=mod|sodium|sodium2` (opens the settings), `-PdlssDebug=spin|walk` (camera motion + Frame Generation frame capture into `run/iris-dlss/captures`).
Look at the screenshot. If Gradle says "Could not connect to the Gradle daemon", run `gradlew --stop` and
try again.

Test packs in [`testpacks/`](testpacks) (own, small GLSL, written for the old Iris port, not yet checked
with Vitrail): `VulkanTest` (composite swaps red/blue, final
draws an 8 px red border, gbuffers write colortex0/1), `VulkanTestPost` (composite/final only),
`VulkanTestAo`, `VulkanTestCenter`, `VulkanTestMip` (shadowtex0 mip 0 left, mip 4 right).

More background for contributors: [`CLAUDE.md`](CLAUDE.md) (status, design, test notes).

## How it works

```
Minecraft (RenderPearl Vulkan backend)                 dlss-bridge (irisdlss.dll, NGX)
──────────────────────────────────────                 ────────────────────────────────
vkCreateInstance / vkCreateDevice ───────────────────▶ adds NGX + DLSS-G extensions, features,
                                                        a presenter queue, an optical-flow queue
world → low-res main target (jittered projection)
  (Vitrail: the pack's gbuffers, deferred, composite, final)
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
* Motion vectors are reconstructed from depth and the camera, plus the nearest moving entities.
* Depth convention: vanilla 26.3 is reverse-Z (`DepthConvention`), Vitrail keeps it.

| Path | What |
|------|------|
| `dlss-bridge/src/bridge.cpp` | device-creation patching, NGX init, DLSS SR, presenter + DLSS-G |
| `dlss-bridge/src/jni.cpp` | JNI for `com.irisdlss.DlssNative` |
| `dlss-bridge/src/probe.cpp` | `dlss_probe.exe`: a tiny Vulkan "game" running SR, FG and the presenter |
| `mod/.../DlssUpscaler.java` | jitter, motion-vector + sharpen pipelines, SR call, FG inputs |
| `mod/.../VulkanPresenter.java` | hands the finished frame to the bridge's presenter |
| `mod/.../SodiumOptions.java`, `DlssSettingsScreen.java` | settings (Sodium pages, Mod Menu screen) |
| `mod/.../ShaderPackTweaks.java`, `mixin/VitrailRenderScaleMixin.java` | Vitrail: pack TAA off, its Render Scale off under DLSS |
| `mod/.../mixin/` | Vulkan instance/device, command encoder, surface, samplers, backend choice, GameRenderer, Camera, HUD overlay, F3, dev test world |
| `voxy-vulkan/` | Voxy LODs on Vulkan (separate mod) |
| `testpacks/` | own test shader packs |

## Known issues

* With Frame Generation, HUD elements only update on rendered frames. wWaypoints' in-world icons are drawn
  as part of the picture instead, with the motion of their waypoint, so they move smoothly with the world;
  other mods' world markers (e.g. Xaero's in-world waypoints) still update at the rendered frame rate.
  wWaypoints places its icons with its own FOV math, which can drift slightly against the world away from
  the screen centre (also without DLSS).
* Joining a world: Vitrail reads and compiles the shader pack a second time so block tags resolve; for packs
  without block tags (e.g. Solas) the mod skips that second read, which shortens the join hitch.
* Motion vectors follow the camera and moving entities (position and body turn, nearest 32), not animated
  parts (legs, heads), water waves or particles: those can smear a little.
* Voxy LODs are off while a shader pack is active, and cast no shadows.
* Only Windows + NVIDIA.

## License

This mod and voxy-vulkan: [MIT](LICENSE). NVIDIA's DLSS runtime files in the jar (`nvngx_dlss.dll`,
`nvngx_dlssg.dll`) are redistributed under the NVIDIA DLSS SDK license (`NVIDIA_DLSS_LICENSE.txt` in the
jar); `fetch-deps.ps1` downloads the SDK, it is not part of this repository. Vitrail (LGPL-3.0), Sodium,
Voxy and shader packs are not included; get them from their authors.
