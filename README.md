# Iris DLSS Bridge (Minecraft 26.3, Fabric)

NVIDIA DLSS Super Resolution and DLSS Frame Generation for Minecraft 26.3, running completely on
Vulkan: the game uses its own Vulkan renderer (RenderPearl's Vulkan backend), and DLSS is recorded
straight into the game's command buffers on the game's own images. No OpenGL, no shared memory.

Status: experimental, personal project. Works on an RTX 4060 Ti (driver 617, Windows 11).
See "Known issues" — help is welcome.

## Install (players)

1. Fabric Loader 0.20.1+ for Minecraft 26.3 (Sodium optional and supported).
2. Drop `iris-dlss-<version>+mc26.3.jar` into `mods/`. Done.
3. Settings: Video Settings → left list → **DLSS** (Sodium screen), or Mod Menu.

While DLSS is on, the game always starts on the Vulkan graphics API (the "Graphics API" option is
overridden; OpenGL is only a fallback if Vulkan cannot start, and DLSS is off then). Iris shader
packs need OpenGL, so they do not run together with this version. Changing the quality mode changes
the texture LOD bias only after a restart (Vulkan samplers are created at startup).

## How it works

```
Minecraft (RenderPearl Vulkan backend)                 dlss-bridge (irisdlss.dll, NGX)
──────────────────────────────────────                 ────────────────────────────────
vkCreateInstance / vkCreateDevice ───────────────────▶ adds NGX + DLSS-G extensions, features,
                                                        a presenter queue, an optical-flow queue
world → low-res main target (jittered projection)
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
* NGX features are released three frame submissions after their last use.
* Frame pacing: with VSync the presenter waits (`VK_KHR_present_wait`) until the generated frame is
  on screen before showing the real one, so each gets one refresh. Like NVIDIA Reflex, the game
  starts each frame (before reading input) only when the presenter is about to need it: render time
  is measured up to GPU completion, the cycle comes from the monitor's refresh rate. Measured at
  165 Hz: 165 fps shown from 83 rendered, 6.1 ± 0.8 ms between frames, ~20 ms latency (before:
  6.1 ± 6.0 ms, 35 ms).
* The hand pass clears the main depth buffer, so the world's depth is copied before it; motion
  vectors use it, the hand gets zero motion, and DLSS / DLSS-G get world + hand depth.
* F3 shows shown / rendered fps, pacing and latency.
* Motion vectors are reconstructed from depth and the camera only (no per-object motion yet).
* Depth convention: vanilla 26.3 is reverse-Z (`DepthConvention`).

### Layout

| Path | What |
|------|------|
| `dlss-bridge/src/bridge.cpp` | device-creation patching, NGX init, DLSS SR, presenter + DLSS-G |
| `dlss-bridge/src/jni.cpp` | JNI for `com.irisdlss.DlssNative` |
| `dlss-bridge/src/probe.cpp` | `dlss_probe.exe`: a tiny Vulkan "game" that runs SR, FG and the presenter through the bridge |
| `mod/src/main/java/com/irisdlss/DlssUpscaler.java` | jitter, motion-vector + sharpen pipelines, SR call, FG inputs |
| `mod/src/main/java/com/irisdlss/VulkanPresenter.java` | hands the finished frame to the bridge's presenter |
| `mod/src/main/java/com/irisdlss/VulkanAccess.java` | raw Vulkan handles behind RenderPearl objects |
| `mod/src/main/java/com/irisdlss/mixin/` | Vulkan instance/device creation, command encoder, surface, samplers, backend choice, GameRenderer (two main targets), Camera (jitter), F3 |
| `mod/src/main/java/com/irisdlss/SodiumOptions.java` | settings page in Sodium's video settings |

## Build (developers)

Requirements: Windows 10/11, NVIDIA RTX GPU, Visual Studio 2022 (C++ workload), JDK 25, git.

```bat
cd dlss-bridge
powershell -ExecutionPolicy Bypass -File fetch-deps.ps1   :: NVIDIA DLSS SDK + Vulkan headers
set JAVA_HOME=C:\path\to\jdk-25
build.bat                                                 :: out\irisdlss.dll, out\dlss_probe.exe
out\dlss_probe.exe                                        :: optional self test

cd ..\mod
:: put a Sodium 0.9.x jar for 26.3 at ..\libs\ (or set sodium_jar in gradle.properties)
gradlew build                                             :: build\libs\iris-dlss-*.jar
gradlew runClient                                         :: dev client in mod\run
```

The jar bundles `irisdlss.dll` and NVIDIA's `nvngx_dlss.dll` / `nvngx_dlssg.dll` (redistributed
under the NVIDIA DLSS SDK license, copied as `NVIDIA_DLSS_LICENSE.txt`).

## Debugging (dev client)

`gradlew runClient -PdlssDebug=spin` (or `=walk`) turns the camera / walks forward and writes the
generated and real frames, motion vectors and depth of four frame-generated presents to
`mod/run/iris-dlss/captures`. `dlss_probe.exe [vsync]` reports pacing and latency without the game.

## Known issues / where help is needed

* **Blur and noise in the distance while moving.** Most likely the camera-only motion vectors
  (wrong for moving entities, water, Voxy LODs past the far plane) or the jitter not matching what
  the shader pack renders. Ideas: write real motion vectors from Iris (velocity buffer), verify the
  jitter sign with the "Jitter direction" option, mark Voxy LOD pixels separately.
* **Black smears** in some scenes (seen with Solas + Voxy). Suspected bad depth / motion vectors
  feeding DLSS or DLSS-G history.
* Frame Generation matrices (`buildFrameGenParams`) are a best effort: flipped Y, depth mapped to
  [0, 1], row-major JOML arrays — worth checking against the DLSS-G programming guide.
* No Iris shader packs and no Voxy on this version: both need OpenGL. Iris-specific code
  (`DepthConvention`, `ShaderPackTweaks`) is kept for when they support Vulkan.
* Tested in game only with vanilla + Fabric at 854x480 (dev client); not yet with Sodium on Vulkan.
* Only Windows + NVIDIA. No AMD/Intel path.

## License

Not chosen yet. NVIDIA SDK files are not part of this repository; `fetch-deps.ps1` downloads them.
