# Spike Catcher

A small client mod for Minecraft 1.21.11 (Fabric). Whenever a frame takes longer than 50 ms, it writes down
what the game was doing during that frame.

Spark shows averages, so a rare 100 ms freeze disappears in it. This catches those freezes one by one.

## Install

Put `spike-catcher-1.0.0.jar` in the `mods` folder, next to Fabric API. It does nothing you can see in game.

## Where the log is

- If `/sdcard/MG` exists (MobileGlues makes it), the log is `/sdcard/MG/spikes.txt`.
- Otherwise it is `spike-catcher.txt` in the game folder.

Play, then send that file. A new session adds to the end; the file is moved to `.old` once it passes 4 MB.

## Reading a spike

```
=== spike 7  14:03:41.532  frame took 143.2 ms ===
main thread: 61 ms of CPU in 118 ms (52%) = partly working, partly waiting or switched out
garbage collection during it: none
it was: 70% running, 25% in a GL call, 5% waiting
code that asked for the work: ...
timeline, ms from the start of the frame: ...
one stack, innermost first: ...
```

- **CPU in X ms** tells you if the thread was working (close to 100%) or waiting (close to 0%).
- **in a GL call** means the thread was inside the graphics driver. Often the GPU is behind.
- **waiting** means it was parked or asleep, usually waiting for another thread.
- **code that asked for the work** is the innermost Minecraft or mod frame, with the mod it belongs to. For code a
  mod injected into Minecraft, the mod is read from the method name (`handler$abc000$sodium$onRender` is Sodium).
- Minecraft's class names are the obfuscated ones (`class_757`). A few are named in `<angle brackets>`.

Every 60 seconds a `[summary]` line counts the frames and spikes in that minute.

## Settings

Add JVM arguments (`-Dname=value`). All optional:

| Argument | Default | Meaning |
| --- | --- | --- |
| `spikecatcher.thresholdMs` | 50 | A frame this long or longer is written down |
| `spikecatcher.suspectMs` | 25 | Sampling starts this far into a frame |
| `spikecatcher.sampleMs` | 4 | Time between samples |
| `spikecatcher.graceSeconds` | 20 | No spikes are written this long after the first frame (loading) |
| `spikecatcher.pauseMs` | 3000 | A longer frame is counted as a pause and not analysed |
| `spikecatcher.maxReports` | 500 | Spikes written per session |
| `spikecatcher.file` | | Where to write the log |

## Limits

- The first 25 ms of a slow frame are not sampled, so a 50 ms spike gets about six samples.
- Java can only stop a thread at safe points, so samples lean towards loops and method calls. Spark has the same
  limit.
- The timer is a hook at the top of the game's frame loop. If a Minecraft version changes that method, the hook
  skips itself and the HUD draw is used instead. The HUD does not draw on the title screen or with F1 pressed, so
  spikes there are not real.
- It only watches the render thread.

## Build

`./gradlew build` in this folder. Needs Java 21 or newer. The jar is in `build/libs/`.
