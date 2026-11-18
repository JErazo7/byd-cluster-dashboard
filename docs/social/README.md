# Launch copy

Ready-to-paste posts for the public launch. Assets in this folder:

| File | Use |
|---|---|
| `github-social-preview.png` | GitHub → Settings → General → Social preview (1280×640) |

Other assets: `docs/demo-modes.gif` (README and post attachments), `docs/modes/*.jpg` (still
frames). The demo video is not committed; attach it natively to each network.

Every number here comes from the README. Do not add one that is not there: the project's whole
claim is that the values were measured on the car.

## X / Twitter (English thread)

**1/**

```
BYD's cluster panel is closed. I didn't reverse engineer it by hand: I measured on the car
and drove an AI agent through the traces, the command codes and the code.

It now runs my own SVG skin. 2.6 MB APK, no Gradle, MIT.

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

## LinkedIn (Spanish)

Links in the body cost reach: keep the repo out of the post, put it in the first comment.

```
Le pedí a una IA que hiciera ingeniería inversa del tablero de mi BYD. Funcionó, y está abierto.

El objetivo no era dibujar un tablero bonito. Era entender quién manda en el panel que está
detrás del volante. Ese panel no es una pantalla más de Android: es un display virtual aparte,
y solo existe mientras un binder del sistema (AutoContainer) está en modo proyección. Los
códigos que lo abren y lo cierran no están documentados en ningún lado.

Así trabajamos: yo medía en el carro (logcat, dumpsys, capturas del panel) y la IA leía las
trazas, proponía el siguiente comando, escribía el script y documentaba el resultado. Cada
hipótesis falsa quedó escrita también, para no volver a probarla.

La parte que ninguna IA adivina fue el apagado: BYD mata el proceso cuando apagas el carro. Eso
se descubre midiendo, no razonando.

El resultado es un repo donde el tablero es un SVG de 1920×720 que editas como texto: fondo,
tipografía, anillos, marchas. Y la guía de implementación está escrita para que un agente la
ejecute en otro carro, no solo para que la lea un humano.

Si tu BYD tiene el mismo sistema de cluster (DiLink 3.0), ya no empiezas de cero: están los
códigos medidos, las pruebas para portarlo y un APK firmado para mirar antes de compilar.

Solo lo probé en un Shark. El resto es tu medición: si portas el tuyo, abre un issue con el
nombre de tu display, los códigos que responda y la señal de encendido.

Código, guía y mediciones en el primer comentario.

#BYD #Shark #OpenSource #Android #DiLink
```

First comment:

```
Código, guía y mediciones: https://github.com/JErazo7/byd-cluster-dashboard
```

## X (Spanish, single post — alternative to the thread)

```
Le pedí a una IA que hiciera ingeniería inversa del tablero de mi BYD Shark.

Ese panel no es una pantalla de Android: es un display virtual que solo existe mientras un
binder (AutoContainer) está en proyección. Los códigos no están documentados.

Ahora el tablero es un SVG de 1920×720 que editas como texto, y la guía está escrita para que
un agente la ejecute en otro carro.

Si tu BYD es DiLink 3.0, no empiezas de cero:
https://github.com/JErazo7/byd-cluster-dashboard/releases/latest
```
