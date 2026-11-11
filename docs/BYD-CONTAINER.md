# AutoContainer — the channel between the head unit and the cluster panel

Measured on this unit (`BYD_AUTO`, DiLink 3.0, Android 10) with a shell probe (`app_process`).

## Interface (verified)

```
service list → AutoContainer: [android.os.IAutoContainer]

int getProjectionDisplayInfo(ProjectionDisplayInfoParcel)
int registerCallback(IContainerCallback)
int sendInfo(int type, int cmd, String arg)
int sendInfo2(int type, byte[] flatbuffer)
int sendJson(int type, String json)
```

`ProjectionDisplayInfoParcel` — `{ int width; int height; String name; IBinder surface; }`.
Reading the current state returns `0` with every field empty (no projection registered).

## What works

`sendInfo(1000, cmd, "")` — the panel command channel:

| cmd | effect |
|---|---|
| 16 | mirrors the head unit on the panel (full screen) |
| 17 | half |
| 18 | turns the mirror off |

Runnable over adb: `service call AutoContainer 2 i32 1000 i32 16 s16 ""`.

## What does NOT work from here

- `sendJson(1000, ...)` returns `-1` for every payload tested (including
  `{"auto_container":true}` and the name of the projection display).
- `registerCallback(...)` with an `IContainerCallback` proxy **kills the process** (Killed), so
  the callback transport is not reachable from an ordinary app/shell process.
- Creating a private `VirtualDisplay` with the name the compositor looks for
  (`shared_fission_bg_XDJAScreenProjection_0`, 1920×720, `FLAG_PRESENTATION`) works and renders
  the skin perfectly (capturable with `screencap -d <id>`), but the cluster **does not show
  it** — the attach is missing, and that is precisely the privileged step.
- `VIRTUAL_DISPLAY_FLAG_PUBLIC` requires `CAPTURE_VIDEO_OUTPUT` (SecurityException).

## Practical consequence

From what is reachable without a platform signature, the cluster only receives a **mirror of the
head unit**. So: the skin must be on the head unit (no bars, 1920×720 at the top of the frame)
and the settings appear on top when opened. Separating "settings on the head unit / skin on the
panel" requires the privileged attach (root, or a system app).

## Who can talk to AutoContainer (verified)

`/system/etc/container_comm_cfg.json` lists the authorised packages; on this unit only
`com.xdja.clusterdemo` (types 0–134217727). Any other name gets
`SecurityException: Not allowed package` and the panel stays on BYD. That is why the SharkLab
build comes out with `--rename-manifest-package com.xdja.clusterdemo`.

The app (application uid, no adb) calls `sendInfo(1000, cmd, "")` over binder and the command is
accepted. The reference cluster app does the same over ADB (`service call AutoContainer ...`)
because it was installed under another name; it is not needed for us.

## What each command does on the panel

| cmd | observed effect |
|---|---|
| 16 | panel shows the virtual display `fission_bg_xdjaVirtualSurface` (1920×720) full screen |
| 17 | half |
| 18 | back to the original BYD interface |
| 35 | accepted, no visible change when the panel is already in mode 16 |

Mode 16 stays active until someone sends 18. While it is active, **any** window on display 1
appears on the panel — including the `TYPE_APPLICATION_OVERLAY` overlay that SharkLab creates.
Without mode 16, that same window is drawn but the panel does not show it (this is exactly the
"it depends on the reference app" symptom).

Checking the physical panel without looking at the car:

```bash
adb shell fission_screencap -d 0 > panel.raw   # 8-byte header + RGBA 1920×720
```

## Window flags that hide the native bar (measured)

The overlay on display 1 needs the SAME flags as the reference window: `FLAG_LAYOUT_NO_LIMITS`
and nothing else. With
`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_FULLSCREEN | FLAG_LAYOUT_IN_SCREEN` the container
composes the native BYD bar (P/ECO, battery, range, ODO, ADAS) on top of the skin. Only with
`FLAG_LAYOUT_NO_LIMITS` does full screen come out clean.

Measurement (16:54–16:57): with the old flags, `cmd 18` and `cmd 16` did not change the bar; with
`FLAG_LAYOUT_NO_LIMITS`, `cmd 18` brings the bar back and `cmd 16` removes it, with the reference
app running or stopped. Cmd 35 changes nothing in that state.

## What makes the cluster accept cmd 16 (measured on 08/11)

The cluster only shows display 1 full screen without the native bar when the process that sends
`sendInfo(1000, 16, "")` keeps a **live screen projection** (MediaProjection):

- SharkLab asks for the capture consent and creates the virtual display
  `shared_fission_bg_XDJAScreenProjection_0` (1920×720, flags 3);
- with that projection registered, `cmd 16` hides the bar (P/ECO, battery, range, ODO);
- without a projection, the same `16` is accepted (returns 0) but the cluster stays on the native
  layout.

Verified with the reference app **disabled** (0 processes):

| action | panel |
|---|---|
| SharkLab open and consent accepted | full skin, no bar |
| `cmd 18` | BYD bar comes back in ~1 s |
| loop of `cmd 16` every 5 s | SharkLab comes back by itself in ≤5 s |

The MediaProjection consent is per process: opening SharkLab after a boot shows the "Comenzar
ahora" dialog once. The reference cluster app does not use MediaProjection: it connects over ADB
Wi-Fi (`127.0.0.1:5555`) with a persisted RSA key (authorise once) and runs
`service call AutoContainer ...` as shell; its watcher reaffirms the panel every ~60 s.

Consequence: while the reference cluster app is installed and gets **paused**, it sends
`46 → 18 → 0` and the cluster refuses the `16` from other processes until the reference app
activates again. With the reference app disabled/uninstalled that block does not exist.

## Panel over ADB (final route, no dialogs)

The reference cluster app does not use MediaProjection: it talks to the local adbd
(`127.0.0.1:5555`) and sends `service call AutoContainer ...` as shell. We replicate the same
ADB client approach:

- persisted RSA 2048 key; the one we ship is the workstation's (already authorised on the head
  unit), bundled only in the local build (`build/assets/adbkey.pem` / `adbkey.pub`, outside git);
- token signature: PKCS#1 v1.5 block of 256 bytes `00 01 FF..FF 00 || DigestInfo SHA-1
  || token` with `RSA/ECB/NoPadding`. `SHA1withRSA` re-signs the token and adbd refuses it;
- the ADB socket is kept open and each command opens a shell stream on it; `cmd 16` every 60 s.

Verified (reference app disabled): connects without a prompt, `cmd 16` takes over the panel,
`cmd 18` brings the BYD bar back and the 60 s loop retakes the skin on its own.

## Colours on the panel: the capture is RGBA, not BGRA

`fission_screencap` returns 4 bytes per pixel and the 16-byte header
(`w, h, format, stride`), but the channels come in **RGBA** order. Decoding it as BGRA swaps R
and B and makes the blue skin (`#0480ff`) show up orange (`#ff8004`) in the analysed image — on
the real panel the colours were always right. White, grey and magenta do not give the mistake
away, which makes the error easy to accept.

Before touching window format (`PixelFormat`) or `setLayerType`, decode a test capture in both
orders and compare against a known colour.

## Taking the panel: the order and the three things that must be alive

Measured on 2026-11-10, after losing a whole session thinking it was a code regression. It is
not: it is the order of operations.

1. **ADB session with the bundled key.** The app talks to `127.0.0.1:5555` using the RSA key
   embedded in the APK (`AdbShell`). Authorised once on the car, it never asks again. Without it
   `cmd 16` does not go out and the panel stays on the BYD interface.
2. **`cmd 16` BEFORE the window.** The container is what creates the panel display, when it
   receives the 16. If the skin window is created first, it goes to a display that does not exist
   yet and the panel comes out **black** (that was exactly the symptom: black with the BYD icons
   on top). `takePanel()` and `PanelBoot` now send 16, wait ~1.2 s and only then create the
   window.
3. **A producer for the surface.** The container composites
   `shared_fission_bg_XDJAScreenProjection_0`; if nobody draws on it, black again. The
   `auto_container` JSON route was tested payload by payload and **all of them return `-1`**
   (rejected), so the producer is the `ClusterProjection` `VirtualDisplay`, which needs the
   capture token (`Mirror.request` → system dialog, once per process).

Diagnostic checklist when the panel does not show up:

```bash
adb -s <ip>:5555 shell dumpsys display | grep -c mDisplayId   # 1 = panel display does not exist
adb -s <ip>:5555 shell service call AutoContainer 2 i32 1000 i32 16 s16 null
adb -s <ip>:5555 exec-out fission_screencap -d 0 > panel.raw   # RGBA, 16-byte header
```

**Do not run the reference cluster app along with SharkLab.** It also sends 16/18 to the same
container and the two fight over the panel; when the reference app is active our skin vanishes.
Leave it uninstalled or stopped. If the panel goes back to the BYD interface out of nowhere,
that is a sign that something sent 18.

## The panel display only exists while the container is projecting

Measured on 2026-11-10 with the reference app **running** (it does not appear in the drawer, it
is started with `am start`):

```
DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface", 1920x720, density 320, type VIRTUAL,
                  owner com.xdja.containerservice (uid 1000),
                  FLAG_PRESENTATION, mDisplayId=2}
```

SurfaceFlinger layers with it active: two layers from the reference cluster app (its panel
window and its sound button window).

Two consequences:

1. **Before that, `dumpsys display` shows a single display.** That is why the skin window
   answered `panel display not found`: the display does not exist until the container
   enters projection with a client hanging on it.
2. **With the display alive our skin paints on top normally** — verified: with the reference app
   open, opening SharkLab draws the `byd` theme on the panel (throttle, power, battery, fuel,
   range, 2.5/2.6 bar pressures, mountain mode, P R N D, ODO).

In other words: `ClusterOverlay` is correct; what SharkLab is missing is getting the container to
publish the display. Until that is solved, the practical order is: **the reference app starts the
projection, SharkLab draws the skin**.

## The container sequence: 46 and then 16

Observed on the unit (the reference cluster app sends exactly this) and confirmed on the car:

```
service call AutoContainer 2 i32 1000 i32 46 s16 null; sleep 1.2
service call AutoContainer 2 i32 1000 i32 16 s16 null
```

**The 46 is what creates the panel display** (`fission_bg_xdjaVirtualSurface`). The 16 alone
projects, but without the 46 no display exists and the skin window answers "panel display not
found". When giving the panel back to BYD, the reference app sends **46, 18 and 0**, in
that order.

Measured with the reference cluster app **disabled** (`pm disable-user <pkg>`):
sending 46 and 16, `dumpsys display` goes from 1 to 2 displays and the SharkLab skin appears on
the panel — it no longer depends on the reference app. The scale still needs fixing: the skin
comes out enlarged and cropped (the panel viewport is 960 CSS px for a 1280×480 viewBox).


## Car on and off (measured 2026-11-10)

| Reading | On | Off (door open) |
|---|---|---|
| `power.getPowerCtlStatus(3)` | **1** | **0** |
| `power.getShutdownInfo(1)` | 0 | 0 |
| `bodywork.getAutoSystemState` | 0 | 0 |
| `bodywork.getPowerLevel` | 2 | 0 |
| `power.getMcuStatus` | 1 | 1 |
| `energy.getEnergyState` | 0/1 | 0 |

`AbsBYDAutoPowerListener.onPowerCtlStatusChanged(int, int)` publishes that same value.
`getPowerCtlStatus` only accepts index 3; the others return `-2147482645`.

App rule: `pc == 1` → apply; `pc == 0` → `PanelRelease.release()` (the panel goes back to BYD
and only the door alert is left). It is polled every 2 s.

`ACTION_SCREEN_OFF` and `ACTION_SHUTDOWN` **do not arrive** on shut-down: the head unit does not
emit them.
