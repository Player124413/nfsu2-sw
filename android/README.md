# Android 64-bit port

This directory is the Android Studio launcher and the native Android target for
this recompilation. It supports `arm64-v8a` and `x86_64`, uses the upstream SDL2
Android backend, and keeps the extracted game data in app-private storage.
The launcher does **not** include game data: use your own legally obtained copy.

## Dependencies

- Android Studio with SDK 35, NDK 27.x and CMake 3.22.1
- an SDL2 checkout matching the release used by the app; it must contain
  `CMakeLists.txt` and `android-project/app/src/main/java/org/libsdl/app/SDLActivity.java`
- generated recompilation output containing `recomp_funcs.h` and the generated
  `.c` files (`tools/regen.sh`)

SDL2 is intentionally an external dependency rather than a vendored copy.

## Build

From the repository root:

```sh
gradle -p android :app:assembleRelease \
  -Psdl2.dir=/path/to/SDL2 \
  -Pnfsu2.gen.dir=/path/to/generated/gen
```

To build from GitHub Actions, open **Actions → Android APK → Run workflow**.
Provide a URL to an archive containing the generated C directory, or configure
`NFSU2_GEN_URL` as a repository secret. The archive must contain exactly one
`recomp_funcs.h` next to the generated `.c` files. The workflow also accepts an
optional URL to your own `default.xbe` and can run `tools/regen.sh`; game data
and generated code are never committed. It uploads a signed, installable APK
and a SHA-256 file as an Actions artifact.

For local builds, install `android/app/build/outputs/apk/release/app-release.apk`.
The launcher supports both the Storage Access Framework directory picker and
**Install ISO / XISO**. Select the root of an extracted disc, containing
`default.xbe` and `NFSUNDER/`, or select an Xbox XISO/ISO image; the app copies
or extracts it into app-private storage and validates the XBE before enabling
**Play**. The image is not uploaded anywhere and game data is not committed to
this repository. Installation needs approximately the image size plus the
extracted game size as temporary free space. App data can be removed from
Android Settings to delete the installed copy.

The launcher also keeps native stdout/stderr in `nfsu2_log.txt` under app-private
storage. After a crash, reopen the launcher and press **Скопировать log.txt**
to place the text in the clipboard for diagnostics. The copied log is limited
to 4 MiB so it remains practical to paste into an issue or chat.

## Runtime design

- `LauncherActivity` — game-folder import, render-scale and 60 Hz pacing options.
- `GameActivity` — SDL surface plus `TouchOverlay`.
- `TouchOverlay` — multi-touch Xbox layout with an analogue steering stick,
  d-pad, face buttons, shoulder buttons, triggers and Start/Back.
- `src/android/android_bridge.c` — SDL entry point, JNI settings and virtual
  XInput state bridge.
- `xboxrecomp/src/input/android_input.c` — mutex-protected touch state merged
  with SDL physical controllers.
- GLES 3 renderer path — GLSL ES 3.00, GLES depth-name compatibility,
  BGRA/S3TC capability checks and RGBA fallback for devices without those
  extensions.

The native path defaults to `RECOMP_GL_SCALE=1.0`, SDL swap interval 1 and the
existing optimized renderer settings. `0.75x` is available for thermally
constrained phones; `1.25x` is a quality option for stronger devices.

Runtime saves are kept in the app-private `files/save` directory, separate from
the imported `files/game` tree. This is intentional: Android's process working
directory may be read-only, so the native bridge passes an absolute writable
save path instead of allowing the Xbox path layer to fall back to `./.local`.
When a title requests `HalReturnToFirmware`, Android uses the Win32
`ExitProcess` equivalent without running C `atexit` teardown. Xbox titles can
leave APU, renderer and timer workers active at that point; skipping global
library teardown prevents those workers from locking already-destroyed SDL or
pthread mutexes while the process exits.

## Performance expectations

A 60 FPS target and frame pacing are implemented, but no software can honestly
guarantee 60 FPS on *every* 64-bit phone: GPU drivers, thermal throttling,
background load, display refresh rate and the selected race/resolution all
matter. First validation should be done on the target phone with the same game
scene; if sustained load heats the device, use `0.75x` and close background
apps. The launcher never claims that a phone is compatible solely because it is
64-bit.
