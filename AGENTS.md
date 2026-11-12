# For AI agents

Entry point for any agent working in this repository.

## Read this first

[`docs/AGENT-IMPLEMENTATION-GUIDE.md`](docs/AGENT-IMPLEMENTATION-GUIDE.md) is the self-contained
implementation document: platform contract, panel command sequence, power on/off handling,
watchdog, skin contract, diagnostics, ADB reference and the acceptance checklist.

Platform evidence, raw measurements: [`docs/BYD-CONTAINER.md`](docs/BYD-CONTAINER.md) and
[`docs/bug-panel-display.md`](docs/bug-panel-display.md).

## Scope and status

- Tested on a **BYD Shark** (`BYD_AUTO`, DiLink 3.0, Android 10, 1920×720 cluster panel).
- Expected to work on any BYD that shares the same cluster system: the `AutoContainer` binder
  and the `fission` virtual display.
- Other models need their display name, panel command codes and power signal measured first;
  the guide's porting section lists the probes.
- Do not claim support for a model that has not been tested on the car.

## Hard rules

- The APK must ship as `com.xdja.clusterdemo`; that is the package the container authorises.
- Never commit third-party assets without redistribution rights. `truck.png` is intentionally
  absent from the public tree and is user-supplied at runtime.
- Measure on the car before changing the panel sequence or the power signal; the current order
  comes from measurements, not guesses.
- Code comments, diagnostics and documentation are in English.
