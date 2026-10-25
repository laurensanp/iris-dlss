# Iris DLSS Bridge (Minecraft 26.3, Fabric)

NVIDIA DLSS Super Resolution and DLSS Frame Generation for Minecraft 26.3, working together with
Sodium, Iris shader packs and Voxy. The world is still rendered by OpenGL (Iris needs it); DLSS
runs on a Vulkan device on the same GPU, sharing images with OpenGL, and the final frame is shown
through a Vulkan swapchain so DLSS Frame Generation can insert generated frames.

Status: experimental, personal project. Works on an RTX 4060 Ti (driver 5xx, Windows 11).
See "Known issues" — help is welcome.

## Install (players)

1. Fabric Loader 0.20.1+ for Minecraft 26.3 (Sodium + Iris + Voxy optional but supported).
2. Drop `iris-dlss-<version>+mc26.3.jar` into `mods/`. Done.
3. Settings: Video Settings → left list → **DLSS** (Sodium screen), or Mod Menu.

Keep the game's "Graphics API" option on OpenGL — Iris shaders do not work on the Vulkan backend.
The shader pack's own TAA is switched off automatically at start (double TAA blurs).

## How it works

```
OpenGL (game, Sodium, Iris, Voxy)                      Vulkan (dlss-bridge, irisdlss.dll)
───────────────────────────────────                    ───────────────────────────────────
world → low-res main target (jittered projection)
copy color, write depth + motion vectors ──shared──▶  DLSS Super Resolution (NGX)
full-res target ◀────────────────── shared ────────── upscaled output
GUI drawn on top
final frame ─────────────────────── shared ───────▶  copy → DLSS-G (depth, mvecs, hudless)
                                                       presenter thread: generated frame,
                                                       then the real one half a frame later,
                                                       Vulkan swapchain on a child window
```

* Interop: `GL_EXT_memory_object_win32` / `GL_EXT_semaphore_win32`, Vulkan images exported as
  OPAQUE_WIN32, queue-family transfers to/from `VK_QUEUE_FAMILY_EXTERNAL`.
* Motion vectors are reconstructed from depth and the camera only (no per-object motion yet).
* Depth convention: vanilla 26.3 is reverse-Z; with an Iris pack it is classic depth
  (`DepthConvention`).

### Layout

| Path | What |
|------|------|
| `dlss-bridge/src/bridge.cpp` | Vulkan device, NGX init, shared images, DLSS SR, presenter + DLSS-G |
| `dlss-bridge/src/jni.cpp` | JNI for `com.irisdlss.DlssNative` |
| `dlss-bridge/src/probe.cpp` | `dlss_probe.exe`: tests SR, FG and the presenter without the game |
| `mod/src/main/java/com/irisdlss/DlssUpscaler.java` | jitter, motion-vector pass, SR call, sharpening, FG inputs |
| `mod/src/main/java/com/irisdlss/VulkanPresenter.java` | replaces the OpenGL swap with the Vulkan presenter |
| `mod/src/main/java/com/irisdlss/mixin/` | GameRenderer (two main targets), Camera (jitter), GlSurface (present), samplers, F3 |
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

## Known issues / where help is needed

* **Blur and noise in the distance while moving.** Most likely the camera-only motion vectors
  (wrong for moving entities, water, Voxy LODs past the far plane) or the jitter not matching what
  the shader pack renders. Ideas: write real motion vectors from Iris (velocity buffer), verify the
  jitter sign with the "Jitter direction" option, mark Voxy LOD pixels separately.
* **Black smears** in some scenes (seen with Solas + Voxy). Suspected bad depth / motion vectors
  feeding DLSS or DLSS-G history.
* Frame Generation matrices (`buildFrameGenParams`) are a best effort: flipped Y, depth mapped to
  [0, 1], row-major JOML arrays — worth checking against the DLSS-G programming guide.
* Only Windows + NVIDIA. No AMD/Intel path.

## License

Not chosen yet. NVIDIA SDK files are not part of this repository; `fetch-deps.ps1` downloads them.
