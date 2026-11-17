# MobileGlues "Nothing" test build

A patched build of the [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) renderer plugin, aimed at
frame-time hitches with Sodium on Adreno (tested target: Nothing Phone 3a, Adreno 810).

Nothing here has been measured on a device yet. Treat each build as an A/B test against the official plugin.

## What is patched

`patches/0001-multidraw-stream-indirect-commands.patch` (`gl/multidraw.cpp`)

Sodium draws chunks with one `glMultiDrawElementsBaseVertex` per region per render pass, several dozen calls a
frame. On Adreno, MobileGlues serves these with `glMultiDrawElementsIndirectEXT`, and upstream rewrites offset 0 of
one command buffer with `glBufferSubData` on every call, while the draws queued by the previous call still read it.
The driver has to copy the buffer or flush and wait each time. The patch appends each call's commands to a stream
(unsynchronized map, orphan on wrap) so no call touches bytes a pending draw references.

`patches/0002-frame-pacing-perf-hints-and-hitch-log.patch` (new `gl/frametime.cpp`, hooks in the swap, multi-draw,
buffer, texture and shader entry points)

- **CPU performance hints.** On Android 13+ the renderer reports each frame's duration to the platform (ADPF
  `APerformanceHint`) against a target frame rate, so the scheduler raises the render thread's CPU frequency when
  frames run long instead of reacting a few frames late. Target is 120 fps by default.
- **Hitch log.** When a frame takes much longer than recent frames, one line goes to `/sdcard/MG/latest.log` saying
  where the time went, and every 30 s a summary line gives average, worst frame and hitch count:

      [hitch] 34.2 ms (avg 9.1) | swap 20.1 | multidraw 1.2 x38 | buffer 0.3 x12 | map 0.1 x40 | texture 0.0 x0 | shader 12.0 x2 | elsewhere 0.5
      [frametime] 3290 frames in 30 s | avg 9.12 ms (110 fps) | worst 34.2 ms | hitches 7

  `swap` is time inside `eglSwapBuffers` (waiting for the GPU or the display). `elsewhere` is everything not timed:
  the game's own code, the JVM including garbage collection, and other GL calls.

Both can be changed in `/sdcard/MG/config.json`:

| Key | Meaning |
| --- | --- |
| `"perfHintTargetFps": 120` | Frame rate the CPU hints aim for. `0` turns the hints off. |
| `"hitchLog": 0` | Turns the hitch log and summary off. |

## Getting the APK

Every push that touches `fork/` runs the "Build patched MobileGlues plugin" workflow. Open the run under the
repository's **Actions** tab and download the `MobileGlues-Nothing-…` artifact (a zip containing the APK).

The APK installs as a separate app (`com.fcl.plugin.mobileglues.nothing`) next to the official plugin, and shows up
in Zalith Launcher's renderer list as **MobileGlues Nothing**. Both read the same `/sdcard/MG/config.json`.

It is signed with `test-signing.jks`, a throwaway key committed here so that each build installs over the last one.

## Checking which draw path is in use

After launching the game once, `/sdcard/MG/latest.log` contains a line like

    [MobileGlues] multidrawOrderElementsBaseVertex   = multiindirect > indirect > basevertex > unroll > compute

The first name is the backend in use. The patch only changes `multiindirect` and `indirect`.
