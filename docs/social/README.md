# Launch copy

Ready-to-paste posts for the public launch. Assets in this folder:

| File | Use |
|---|---|
| `github-social-preview.png` | GitHub → Settings → General → Social preview (1280×640) |

Other assets: `docs/demo-modes.gif` (README and post attachments), `docs/modes/*.jpg` (still
frames). The demo video is not committed; attach it natively to each network.

Every number here comes from `docs/bug-panel-display.md` or the README. Do not add one that
is not there: the project's whole claim is that the values were measured on the car.

## X / Twitter (English thread)

**1/**

```
BYD's cluster panel is not an Android display.

It's a separate virtual display (`fission`), and the panel only shows it while the
`AutoContainer` binder is in projection mode.

I replaced it with my own SVG skin. 2.6 MB APK, no Gradle, MIT.

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

If you have another BYD on DiLink 3.0, the porting probes are in the repo. Open an issue with
your display name, the panel codes it answers and your power signal.

https://github.com/JErazo7/byd-cluster-dashboard
```

## LinkedIn (Spanish)

Links in the body cost reach: keep the repo out of the post, put it in the first comment.

```
El panel del cluster de mi BYD Shark ya no es el de BYD.

Arranca con un detalle que casi nadie sabe: ese panel no es una pantalla más de Android. Es
otro display, y solo se muestra mientras el binder `AutoContainer` está en modo proyección.
Cuando lo entendí, el tablero dejó de ser un APK cerrado y pasó a ser un SVG de 1920×720 que
edito como texto.

Qué quedó funcionando en el carro:

· Seis escenas que no se eligen en un menú: las manda el carro. La rueda de terreno cambia
  montaña, nieve, arena y lodo; el testigo ECO/SPORT cambia la paleta.
· Datos reales por las APIs `bydauto` del equipo: velocidad, marcha, potencia con signo (para
  regeneración), batería, autonomía, odómetro, presiones y modos.
· Se aplica y se suelta solo: al encender vuelve a tomar el panel sin abrir nada; al apagar lo
  devuelve para que salga la alerta de puerta abierta.
· APK de 2.6 MB sin Gradle. MIT.

La parte difícil no fue dibujar. Fue el apagado: BYD mata el proceso cuando apagas el carro,
así que hay un vigía que lo levanta y el servicio recupera el panel solo. Ese camino está
documentado con las mediciones, incluyendo lo que probé y NO funciona, para que nadie repita
el fin de semana que perdí.

Y una cosa más: no lo probé en otro modelo. Solo en un Shark. Si tienes otro BYD con
DiLink 3.0, en el repo están las pruebas para portarlo. Abre un issue con el nombre de tu
display, los códigos que responda y la señal de encendido, y lo adaptamos.

Repo en el primer comentario.

#BYD #Shark #OpenSource #Android #DiLink
```

First comment:

```
Código, guía y mediciones: https://github.com/JErazo7/byd-cluster-dashboard
```

## X (Spanish, single post — alternative to the thread)

```
El tablero del cluster de mi BYD Shark ahora es código mío.

SVG de 1920×720, datos reales del carro, 2.6 MB de APK, MIT.

La parte difícil no fue dibujar: BYD mata el proceso al apagar el carro. Hay que levantarlo
con un vigía y devolver el panel antes de que salga la alerta de puerta.

https://github.com/JErazo7/byd-cluster-dashboard
```
