# Panel al encender/apagar — resuelto

**Estado: resuelto y verificado en el auto (encendido normal y remoto).**

## Solución aplicada

| Pieza | Qué hace |
|---|---|
| Detección | `power.getPowerCtlStatus(3)`: `1` encendido, `0` apagado. La app lo consulta cada 1 s |
| Apagado | `pc == 0` → `PanelRelease.release()`: esconde la ventana y devuelve el panel a BYD (queda solo la alerta de puerta) |
| Encendido | `pc == 1` → `PanelBoot.apply()`, que crea la ventana y manda `16` → `45` |
| Proceso muerto | el vigía en adbd lanza `WakeActivity` (`Theme.NoDisplay`, sin layout, `finish()` inmediato) cada vez que no hay proceso |
| Arranque | `WakeActivity` sube `PanelService`, que es quien corre el bucle de `pc` |

Claves que costó encontrar:

1. Los **eventos BYD no arrancan la app**: un listener sólo entrega si el proceso vive. Por eso hace
   falta el vigía externo (igual que el `evs_keep` del EvSound).
2. `am start-foreground-service` sobre un paquete parado se rechaza (`Not found; no service started`)
   aunque el servicio esté exportado. `am start` de una **activity** sí se acepta: es lo que hace
   EvSound con `ArmActivity --ez wake_only true`.
3. El vigía no debe lanzar la **activity principal**: abre la interfaz en la central y le roba el
   diálogo "¿Cargar el coche ahora?". Por eso la `WakeActivity` invisible.
4. Al lanzar el daemon hay que dejar la sesión adb viva un segundo (`sleep 1` tras `setsid`), o el
   adbd se lleva el hijo.
5. Frenos anti-piscado: el vigía sólo despierta si la última vez fue hace >15 s y `PanelBoot.apply`
   es idempotente (<12 s o ventana ya creada).

Todo esto se verificó con el auto: apagado + puerta (sin skin), encendido normal y arranque remoto
por aire (skin aplicada una sola vez).

## Síntomas

1. Tras apagar y volver a encender (normal o remoto por la app BYD), la skin no aparece.
2. Una vez se midió `fission_bg_xdjaVirtualSurface` ausente; en otras mediciones estaba presente
   con la app muerta.

## Medido

| Hecho | Evidencia |
|---|---|
| `power.getPowerCtlStatus(3)` = 1 encendido / 0 apagado | heartbeat `pc=1` / `pc=0` |
| Con la app viva, aplicar y soltar funcionan | `serviço: carro ligado -> aplica`, `carro desligado -> solta` |
| Al apagar, el proceso **muere** | el heartbeat se corta y vuelve con el contador reiniciado |
| El vigía en adbd sobrevive | `/data/local/tmp/shark_wd.sh` con pid vivo |
| `am start-foreground-service` sobre el paquete parado falla | `Not found; no service started` (incluso con el servicio exportado) |
| `am kill` + vigía con el servicio exportado revive la app | pid nuevo y 4 ventanas del panel |
| El display no se destruye con `18` ni se recrea con `46` | contador `5 → 5` |
| Crear un `VirtualDisplay` con el nombre esperado no se adjunta | `docs/BYD-CONTAINER.md` |
| `ssc_whitelist` sólo tenía `com.mdemetrio.evsoundbyd` | se agregó `com.xdja.clusterdemo`; sin efecto medido |
| Lanzar EVSound hizo aparecer el display | contador `0 → 5` sin mandar nada |

## Cómo despierta el EvSound (dex)

No arranca servicios: **lanza una activity con flag**.

```sh
am start -n com.mdemetrio.evsound.navlab/...ArmActivity --ez wake_only true
```

`am start` de una activity *launcher* siempre está permitido; un servicio de un paquete parado
se rechaza. Eso explica por qué su arranque remoto funciona y el nuestro no.

## Intento en SharkLab (sin verificar)

`MainActivity` interpreta `--ez wake true`: arranca `PanelService` y llama a `finish()`. En la
prueba el rastro `wake: activity acordou` no apareció y la activity quedó en primer plano, así
que esa rama no se ejecutó.

Siguiente paso: una activity **transparente y sin layout** (como `ArmActivity`), declarada con
`android:theme="@android:style/Theme.NoDisplay"`, lanzada por el vigía con esa flag.

## Reproducción controlada

```bash
adb shell dumpsys display | grep -c fission
adb shell pidof com.xdja.clusterdemo
# apagar y encender remoto
adb shell dumpsys display | grep -c fission
adb shell pidof com.xdja.clusterdemo
adb shell dumpsys window windows | grep -c 'EvSound painel'
adb shell tail -20 /data/local/tmp/shark.log
adb shell grep -E 'vivo|serviço:|boot:|wake' \
  /sdcard/Android/data/com.xdja.clusterdemo/files/painel.log | tail -12
```

- `fission` = 0 → problema del container (no podemos crearlo).
- `fission` = 5 y `pidof` vacío → nadie despertó la app.
- `fission` = 5, `pidof` con valor y sin ventana → falla `ClusterOverlay.show`.

## No tocar

- Apagado: `pc == 0` → `PanelRelease.release()`.
- Encendido con la app viva: `pc == 1` → `overlayShow()`.
- Orden: ventana → `16` → `45`; nunca `46` antes de crear la ventana.
- El vigía no debe abrir la interfaz de la central (le roba el diálogo "¿Cargar ahora?").
