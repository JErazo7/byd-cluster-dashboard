# Panel on power on/off — solved

**Status: solved and verified on the car (normal and remote power-on).**

## Solution applied

| Piece | What it does |
|---|---|
| Detection | `power.getPowerCtlStatus(3)`: `1` on, `0` off. The app polls it every 1 s |
| Off | `pc == 0` → `PanelRelease.release()`: hides the window and gives the panel back to BYD (only the door alert is left) |
| On | `pc == 1` → `PanelBoot.apply()`, which creates the window and sends `16` → `45` |
| Dead process | the watchdog in adbd launches `WakeActivity` (`Theme.NoDisplay`, no layout, immediate `finish()`) every time there is no process |
| Boot | `WakeActivity` brings up `PanelService`, which is what runs the `pc` loop |

Keys that were hard to find:

1. **BYD events do not start the app**: a listener only delivers if the process is alive. That is
   why the external watchdog is needed (the same as the reference cluster app's keepalive).
2. `am start-foreground-service` on a stopped package is refused (`Not found; no service
   started`) even when the service is exported. `am start` of an **activity** is accepted: that
   is what the reference cluster app does with its `--ez wake_only true` wake activity.
3. The watchdog must not launch the **main activity**: it opens the head unit interface and
   steals the "load the car now?" dialog. Hence the invisible `WakeActivity`.
4. When launching the daemon the adb session has to stay alive for a second (`sleep 1` after
   `setsid`), or adbd takes the child away.
5. Anti-flicker brakes: the watchdog only wakes the app if the last time was >15 s ago and
   `PanelBoot.apply` is idempotent (<12 s or window already created).

All of this was verified on the car: off + door (no skin), normal power-on and remote over-the-air
start (skin applied only once).

## Symptoms

1. After switching off and on again (normal or remote through the BYD app), the skin does not
   appear.
2. In one measurement `fission_bg_xdjaVirtualSurface` was absent; in other measurements it was
   present with the app dead.

## Measured

| Fact | Evidence |
|---|---|
| `power.getPowerCtlStatus(3)` = 1 on / 0 off | heartbeat `pc=1` / `pc=0` |
| With the app alive, applying and releasing work | `service: car on -> applying`, `service: car off -> releasing` |
| On shut-down the process **dies** | the heartbeat stops and comes back with the counter reset |
| The watchdog in adbd survives | `/data/local/tmp/shark_wd.sh` with a live pid |
| `am start-foreground-service` on the stopped package fails | `Not found; no service started` (even with the service exported) |
| `am kill` + watchdog with the service exported revives the app | new pid and 4 panel windows |
| The display is not destroyed by `18` nor recreated by `46` | counter `5 → 5` |
| Creating a `VirtualDisplay` with the expected name does not attach | `docs/BYD-CONTAINER.md` |
| `ssc_whitelist` only had the reference app's package | `com.xdja.clusterdemo` was added; no measured effect |
| Launching the reference cluster app made the display appear | counter `0 → 5` without sending anything |

## How the reference cluster app wakes up

It does not start services: **it launches an activity with a flag**.

```sh
am start -n <package>/...WakeActivity --ez wake_only true
```

`am start` of a *launcher* activity is always allowed; a service from a stopped package is
refused. That explains why its remote start works and ours did not.

## Attempt in SharkLab (not verified)

`MainActivity` interprets `--ez wake true`: it starts `PanelService` and calls `finish()`. In the
test the trace saying the activity had woken up never appeared and the activity stayed in the
foreground, so that branch never ran.

Next step: a **transparent activity with no layout** (like the reference one), declared with
`android:theme="@android:style/Theme.NoDisplay"` and launched by the watchdog with that flag.

## Controlled reproduction

```bash
adb shell dumpsys display | grep -c fission
adb shell pidof com.xdja.clusterdemo
# switch off and do a remote power-on
adb shell dumpsys display | grep -c fission
adb shell pidof com.xdja.clusterdemo
adb shell dumpsys window windows | grep -c -i painel
adb shell tail -20 /data/local/tmp/shark.log
adb shell grep -E 'service:|boot:|wake:' \
  /sdcard/Android/data/com.xdja.clusterdemo/files/panel.log | tail -12
```

- `fission` = 0 → container problem (we cannot create it).
- `fission` = 5 and `pidof` empty → nobody woke the app.
- `fission` = 5, `pidof` with a value and no window → `ClusterOverlay.show` failed.

## Do not touch

- Off: `pc == 0` → `PanelRelease.release()`.
- On with the app alive: `pc == 1` → `overlayShow()`.
- Order: window → `16` → `45`; never `46` before creating the window.
- The watchdog must not open the head unit interface (it steals the "load now?" dialog).
