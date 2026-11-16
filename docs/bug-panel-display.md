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


## Recaída 2026-11-13 (arranque remoto)

Estado: `dumpsys display | grep -c fission` = **0**, app viva aplicando, panel físico en negro con
los widgets de BYD.

Probado en ese estado, sin resultado:

| Prueba | Resultado |
|---|---|
| Arrancar nuestra app y aplicar | `display=0`, sin ventana |
| Habilitar y lanzar EVSound (`pm enable` + `monkey`) | `display=0` (EVSound tampoco consigue) |
| `cmd 46` → `16` → `45` por shell | `display=0` |
| Abrir la puerta del conductor | `display=0` |
| Reiniciar la central (soft) | `display=0` |
| Mover la camioneta (sesión de marcha) | `display=0` |

`logcat` del container en ese estado: sólo `checkSendPermissionAndAllowType … callingUid = 2000`,
sin error ni negativa — acepta el comando y no hace nada.

Conclusión: el container quedó **sin proyección** y no se recupera desde la app ni desde el shell;
sus comandos son aceptados y descartados. Lo único pendiente de probar es un **ciclo completo de
energía** (apagado profundo, no soft restart).

## Lo que sí está resuelto (11/11, verificado)

- Apagado + puerta: sólo la alerta.
- Encendido normal y remoto **con el container sano**: aplica solo, una vez.
- El vigía ya no arranca la activity principal, respeta el diálogo de carga y limita intentos.


## Diferencia pendiente con EVSound: el paquete RCC

Comparando su helper (`EvsPanelPack`, dex que deja en `/data/local/tmp`) con lo nuestro, las
piezas son las mismas salvo una:

| Pieza | EVSound | SharkLab |
|---|---|---|
| Proyección (`46` → `16` → `45`) | sí | sí |
| Daemon que relanza la app | `evs_keep` + `evs_heal` | `shark_wd` (con candado y límite de 15 s) |
| Despertar sin abrir UI | activity con flag (`wake_only`) | `WakeActivity` (`Theme.NoDisplay`) |
| Cliente del container | binder crudo, `TRANSACTION_registerCallback = 4` | **igual** (`PanelCallback`, medido: `reply=0`, sobrevive) |
| **Paquete de tema del clúster** | **`select <rcc>` / `apply <rcc>` / `restore-default`** sobre `Di3F_…Translate_<nombre>.rcc` | **no** |

Cadenas leídas de su dex que delatan ese paso:

```
REGISTER status=  SELECT_CALLBACK_RECEIVED=  RESPONSE cmd=
select <rcc> | select-default | apply <rcc> | restore <rcc> | restore-default
Di3F_DynastyOceanTranslate_V0001_Translate_[A-Za-z0-9]{1,48}\.rcc
```

Qué falta medir: si **aplicar un RCC** es lo que hace que el container prepare la superficie del
panel (es decir, si `display=0` se resuelve con eso y no solo con el callback).

Experimento cuando vuelva a aparecer `display=0`:

1. `PanelCallback.register()` (ya lo hace la app sola).
2. Enviar por binder crudo el `apply` del RCC correspondiente (hay que descubrir el nombre exacto
   que espera el container: el patrón está arriba).
3. Medir `dumpsys display | grep -c fission` antes y después.

Si sube: esa es la pieza que faltaba y se implementa en `PanelBoot`. Si no sube: el gate es estado
del vehículo (llave/sesión) y no una llamada desde la app.

**Medido el 16/11: el RCC no era la pieza que faltaba.** No hizo falta aplicar ni seleccionar
ningún `Di3F_…rcc`: el display vuelve con la petición de proyección (`16` → `35`, y `31` como
recreación forzada). Ver la sección "Proyección perdida" al final.


## Diagnóstico en un minuto (si vuelve a fallar)

```bash
IP=100.73.229.44:5555
adb connect $IP

# 1) log del app (siempre disponible; el archivo se puede quedar mudo)
adb -s $IP logcat -d -s SharkLab | tail -20
adb -s $IP logcat -c            # antes de reproducir, para no leer historia vieja

# 2) ¿existe el display del panel?
adb -s $IP shell 'dumpsys display | grep -c fission'

# 3) ¿hay vigía armado?  (con P vacío, /proc/ existe: hay que descartar el vacío)
adb -s $IP shell 'P=$(cat /data/local/tmp/shark_wd.pid 2>/dev/null); case "$P" in ""|*[!0-9]*) echo MORTO;; *) [ -d /proc/$P ] && echo VIVO || echo MORTO;; esac'
adb -s $IP shell 'ls /data/local/tmp/shark_auto'   # sin este flag el vigía sale y no relanza
adb -s $IP shell 'tail -5 /data/local/tmp/shark.log'

# 4) ¿hay ventana nuestra en el panel?
adb -s $IP shell "dumpsys window windows | grep -c 'EvSound painel'"
```

Lectura de los resultados:

| Síntoma | Significado | Dónde mirar |
|---|---|---|
| `fission` = 0 | el container perdió la proyección | `panel.log`: `projection: 16 -> …` / `31` / `30`. La app la pide sola; si no aparece en 60 s, el ADB no está llegando |
| `fission` = 5, sin ventana, app viva | falló `ClusterOverlay.show` | `painel.log`: `display do painel nao encontrado`, `overlay do painel falhou` |
| `fission` = 5, sin ventana, app muerta | nadie despertó la app | `shark.log`: ¿hay `relaunching`? ¿`am rc=0`? ¿vigía MORTO? |
| vigía MORTO | se desarmó o nunca subió | `shark.log` + logcat `adb: connected` |
| logcat sin `adb: connected` | el app no llega al adbd | clave ADB (compilar con `EMBED_ADB_KEY=1`) |

Notas operativas:

- Compilar siempre con `EMBED_ADB_KEY=1 ./app/build.sh`, o adbd pedirá autorización en cada
  instalación.
- El archivo `files/painel.log` puede quedarse mudo mientras `logcat -s SharkLab` sigue
  funcionando: usar logcat primero.
- El vigía debe ser **uno**: si `shark.log` muestra varias tandas de `armed` muy seguidas, algo lo
  está rearmando en bucle.
- El interruptor "aplicar al encender" se puede poner desde adb sin tocar la UI del auto:
  `am start -n com.xdja.clusterdemo/com.sharklab.MainActivity --ez autostart true`. Con el
  interruptor apagado **no hay que esperar skin al encender**: el vigía no se arma (sin
  `/data/local/tmp/shark_auto` sale enseguida) y `PanelService` no aplica por `pc == 1`.
- Tras apagar, la app muere y no vuelve sola si el vigía no está armado: el tema aplicado a mano
  no sobrevive al ciclo de energía.
- **Soltar el panel con el auto encendido deja el panel en negro** (medido 16/11 09:11): el
  container se queda con el dueño de la proyección pero sin ventana, y `16`/`18`/`0` no traen de
  vuelta el dibujo nativo de BYD; solo vuelve con un ciclo de energía. O sea: el botón SOLTAR solo
  tiene sentido al apagar, y por eso el servicio reaplica si el interruptor "aplicar al encender"
  está prendido.


## Consumo mientras la skin está puesta (medido 2026-11-16)

| Estado | CPU (de un núcleo) |
|---|---|
| App en segundo plano, skin en el panel | **4,9 %** |
| App con su propia pantalla abierta en la central (preview a 10 Hz) | ~15-17 % |
| Vigía en adbd | 0,2 % |
| EVSound / daemons ajenos | ninguno (deshabilitado) |

Procesos que quedan vivos con todo funcionando: **dos** — `com.xdja.clusterdemo` y un solo
`sh /data/local/tmp/shark_wd.sh`. No hay daemons fantasma; el vigía es un script de 5 líneas que
hace `pidof` cada 2 s.

Recortes aplicados ese día: `PanelService` y el bucle de la actividad leen
`power.getPowerCtlStatus(3)` (una llamada binder) en vez del snapshot completo (37); la presión de
neumáticos se cachea 5 s; el latido de la traza pasó de 2 s a 10 s; y el push a la skin se saltea
cuando el payload no cambió.

## Proyección perdida: el fallo que solo EVSound recuperaba — resuelto 2026-11-16

**Síntoma.** Después de un tiempo (no hace falta arranque remoto), el panel se queda en BYD y no
vuelve:

| Medición | Valor |
|---|---|
| `dumpsys display \| grep -c fission` | **0** — el container no tiene el display del panel |
| `pidof com.xdja.clusterdemo` | vacío (nadie despertó la app) o vivo aplicando sin efecto |
| container (`com.xdja.containerservice`, `fission_service`) | vivo y sano |
| `service call AutoContainer … 16 / 45` | responde `Parcel(…)` y **no hace nada**: no hay display sobre el que proyectar |

Nada lo recuperaba: ni reiniciar la central, ni la puerta, ni mover la camioneta, ni el ciclo
`46 → 16 → 45` por shell. Solo lanzar EVSound lo devolvía.

**Cómo se decodificó EVSound** (dex de `com.mdemetrio.evsoundbyd` 3.28, `jadx`):

| Pieza | Su código |
|---|---|
| Pedir la proyección | `service call AutoContainer 2 i32 1000 i32 16 s16 null` → 1 s → `35`, cada uno **verificado**: reintenta hasta 5 veces cada 2 s hasta que la respuesta empieza con `Result: Parcel(00000000` |
| Ciclo "forzar" | `30` → `31` → `16` → `35`, mismos reintentos |
| Devolver el panel | `46` → 1.2 s → `18` → 1 s → `0` |
| Espera | al pedir la proyección sondea el display hasta 6 s (30 × 200 ms) antes de crear la ventana |
| Vigía | script en adbd que relanza su `WakeActivity`; mismo patrón que el nuestro |

**Medido después, con el container sano:** `30` y `31` destruyen el display virtual y crean uno
nuevo (`xdja_AutoDisplayService: updateDisplay: createVirtualDisplay`, ids 1 → 2 → 3 …). `31`
(la petición de modo/resolución) es el que siempre lo recrea; `30` no siempre. Con el display
recreado, la ventana de nuestra app muere con el display viejo y hay que volver a crearla.

**Qué cambió en SharkLab**

| Archivo | Cambio |
|---|---|
| `PanelProjection.java` (nuevo) | `ensure()`: si no hay display, `16` → `35` con reintentos verificados y sondeo de 6 s, 5 rondas; después `31`, después `30`. Bloqueante, se llama fuera del hilo principal |
| `PanelBoot.apply` | pide la proyección **antes** de crear la ventana |
| `PanelService` | si el coche está encendido (`pc == 1`) y no hay display o la ventana está en un display muerto durante 3 sondeos, vuelve a aplicar |
| `ClusterOverlay` | `attached(ctx)`: la ventana existe **y** está en el display actual. `onDisplayRemoved` ya no devuelve el panel (era lo que hacía la pérdida permanente): suelta la ventana y deja que el servicio la recupere |
| `AdbShell` | `run()` síncrono sobre la sesión viva, y **`A_WRTE` corregido** (estaba en `0x57525445`; en el cable es `0x45545257`): hasta hoy la app ejecutaba los comandos pero nunca leía la respuesta, así que no podía verificar nada |
| `AdbShell` | el flag del vigía pasó de `/sdcard/…/autostart` a `/data/local/tmp/shark_auto`: si la tarjeta no estaba montada, el script no veía el flag y moría |
| `MainActivity` | el botón APLICAR usa la misma secuencia en vez de un `46` suelto; los estados que lee la skin usan `attached()` |

**Verificado en la carrocería (16/11, 08:19 y 08:40):** con la skin aplicada, se recreó el
display (`31`, id 3 → 4 → 5). Traza de la app:

```text
glass: panel display removed, window dropped
service: car on, panel lost 1 … 3
service: car on -> applying
boot: projection=true overlay=true
boot: 16/45 sent
```

Y la skin volvió sola en el display nuevo (`screencap -d 5`).

**Confirmado en el auto (16/11, apagado y encendido reales): la skin sube sola**, sin abrir la
app y con EVSound deshabilitado. Ese era el caso que antes solo resolvía EVSound.

**Recuperación probada matando el container** (16/11, 08:44): `am crash com.xdja.containerservice`
mata el proceso (pid 1794 → 1963), el display virtual se va con él y la app lo recupera sola:

```text
08:44:23 glass: panel display removed, window dropped
08:44:24 service: car on, panel lost 1 … 3
08:44:26 service: car on -> applying
08:44:27 boot: projection=true overlay=true
08:44:30 boot: 16/45 sent
```

Skin de vuelta en el display 6 (nuevo) sin tocar nada. Es el mismo camino que corre cuando el
display falta al arrancar.

**Por qué el estado `fission` = 0 no se puede provocar en remoto** (probado el 16/11):

| Intento | Resultado |
|---|---|
| `am force-stop com.xdja.containerservice` | ignorado (app de sistema, el pid no cambia) |
| `am crash com.xdja.containerservice` | el proceso muere y **se lleva el display**, pero al arrancar lo recrea solo (`8 → 9`) y conserva el estado de proyección |
| `46 → 18 → 0` con la app deshabilitada y sin ventanas | el display **no** se destruye |
| `31` / `30` con el display ya presente | a veces lo recrean (id nuevo), a veces nada |

Conclusión: el estado del 16/11 08:00 solo aparece tras un ciclo de energía real, que es lo que
borra el estado de proyección del container. Para cerrarlo del todo hace falta esperarlo o
apagar/encender la central estando en el auto; la traza dirá en `panel.log` qué devolvió cada
comando (`projection: 16 -> Result: Parcel(…)`, `35`, `31`, `30`).

