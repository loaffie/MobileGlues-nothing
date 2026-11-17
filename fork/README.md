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
