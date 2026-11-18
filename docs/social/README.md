# Launch copy

Ready-to-paste posts for the public launch. Assets in this folder:

| File | Use |
|---|---|
| `github-social-preview.png` | GitHub → Settings → General → Social preview (1280×640) |

Other assets: `docs/demo-modes.gif` (README and post attachments), `docs/modes/*.jpg` (still
frames). The demo video is not committed; attach it natively to each network.

Every claim here is backed by the README. Tested on one car: a BYD Shark. Other BYD models
sharing DiLink 3.0 are the target, not a measured fact — the copy says 'any BYD on DiLink 3.0'
because the platform contract is the same, and only the probes differ.

## X / Twitter (English thread)

**1/**

```
BYD's cluster panel is closed. I put my Shark on a Tailscale network and reverse engineered
it from a VM, with AI agents reading the traces and the command codes.

It now runs my own SVG skin, and the skin is yours to rewrite. 2.6 MB APK, no Gradle, MIT.

🧵
```

(attach the demo video to this tweet)

**2/**

```
The whole trick is three numbers:

16 → projection on
45 → full screen, BYD's own widgets off
18 → back to the native panel

Everything else is a window on the panel's virtual display. Its id is assigned at runtime,
so you resolve it by name (`fission_bg_xdjaVirtualSurface`), not by number.
```

**3/**

```
The order is state dependent, and that is the trap:

If the panel display does not exist yet, 16 has to go before the window, or the panel comes
out black. On boot, the measured sequence is window first, then 16, then 45.

Both are in the repo, with the reason.
```

**4/**

```
Data is not scraped, it comes from the vendor APIs (`android.hardware.bydauto.*`):

speed, gear, power (signed, so regen), SOC, fuel, range, odometer, tyre pressures, terrain
and drive mode.

~12 ms per read, 37 binder calls, pushed at 2.5 Hz, and the skin interpolates between pushes
so the needle does not step.
```

**5/**

```
Six scenes, one file each. The car picks the scene: the terrain wheel changes mountain / snow
/ sand / mud, the ECO/SPORT telltale changes the palette.

1920×720 viewBox, so it scales. Edit text, reload, done. No app rebuild.
```

(attach `docs/demo-modes.gif`)

**6/**

```
The part nobody writes down: BYD kills the app when you switch the car off.

The signal is `power.getPowerCtlStatus(3)`: 1 = on, 0 = off (door open). A 2 s watchdog in
adbd relaunches the service, and the service — never the activity — takes the panel back.
```

**7/**

```
Things that do NOT work, so you do not lose a weekend:

· ACTION_SCREEN_OFF / SHUTDOWN — never arrive
· power.getShutdownInfo(1) — 0 on and off
· bodywork.getAutoSystemState, power.getMcuStatus — identical in both states
· BOOT_COMPLETED — discarded for installed apps
```

**8/**

```
Now the honest part: I only ran this on a BYD Shark. One car, one unit.

There is a signed APK on the releases page if you want to look at the panel before building
anything, but that is not the point: the point is that you edit the skin, or write your own.

If you have another BYD on DiLink 3.0, the porting probes are in the repo. Open an issue with
your display name, the panel codes it answers and your power signal.

https://github.com/JErazo7/byd-cluster-dashboard/releases/latest
```

## LinkedIn

Short on purpose: nobody reads a long post. Links cost reach, so the repo goes in the first
comment.

```
I reverse engineered the cluster dashboard of my BYD Shark: the car on Tailscale, the agents on a
VM.

That panel is not another Android screen. To draw on it you have to ask a system service
(AutoContainer) for projection, with codes nobody documents.

Today the whole dashboard is one SVG I edit as text: six scenes that the car picks, real vehicle
data, a 2.6 MB APK.

And the code is open. Change the theme to your taste, or do whatever you want with the instrument
panel. It is meant to work on any BYD that runs DiLink 3.0.

Repo and APK in the first comment.

#BYD #Shark #OpenSource
```

First comment:

```
Code, guide and the signed APK: https://github.com/JErazo7/byd-cluster-dashboard/releases/latest
```

## X (single post)

The same story as LinkedIn, cut to 279 characters (280 is the limit; a link always counts as
23). With X Premium, use the LinkedIn text as it is.

```
Reverse engineered my BYD Shark's cluster: from a VM, car on Tailscale.

That panel is not Android: a system service projects onto it, with undocumented codes.

Now one SVG, six scenes, real data. Change the theme or anything else. Any BYD on DiLink 3.0.

https://github.com/JErazo7/byd-cluster-dashboard/releases/latest
```
