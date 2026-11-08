# SharkLab

Panel propio para el BYD Shark, con **código propio**: skins en HTML/SVG que se editan como
texto y una app Android que las pinta en la pantalla central y en el panel detrás del volante,
**sin depender del EvSound**.

| Carpeta | Qué es | Estado |
|---|---|---|
| `app/` | SharkLab: app Android que pinta la skin (APK 36 KB, sin Gradle) | **compila y firma** |
| `themes/` | Tema `shark` (panel) + preview con sliders | funcionando |
| `tools/` | Reempaquetador para meter tus temas en el EvSound que ya compraste | funcionando |

## Estado real de las funcionalidades

| Función | Estado |
|---|---|
| Panel/skin propio (SVG, 1920×720, claro/oscuro, panel entero, ancho variable) | ✅ |
| Skin en el panel detrás del volante a pantalla completa, sin depender del EvSound | ✅ |
| El app toma el panel solo: manda `AutoContainer` cmd 16 al abrir y lo reafirma cada 5 s | ✅ |
| Carga de skins desde el almacenamiento (sin recompilar) | ✅ |
| Tarjeta de control dentro del panel (skin, panel, cámara, volante) | ✅ |
| Lectura de datos reales del BYD (CAN/DiLink) | ⏳ pendiente |
| Cámaras, ADAS, mapa, Android Auto / CarPlay | ⏳ pendiente (AA/CarPlay requieren licencia de Google/Apple) |

## Compilar la app

```bash
SDK=/opt/android-sdk ./app/build.sh      # sale en app/out/sharklab-cluster.apk
```

Sin Gradle: `aapt2` + `javac` + `d8` + `apksigner`. Requisitos: JDK 17, Android SDK
(platform + build-tools), `keytool`.

El APK sale con el paquete **`com.xdja.clusterdemo`**: es el único nombre que
`/system/etc/container_comm_cfg.json` autoriza a hablar con el servicio `AutoContainer`.
Con otro nombre la app dibuja la skin, pero el panel sigue en la interfaz original de BYD.
Se puede cambiar con `PKG=... OUT=... ./app/build.sh`.

La app carga `assets/dash/<id>/index.html`, le inyecta `lab/*.js` y alimenta la skin con
datos simulados (`Lab.state()`), igual que el feed de referencia.

## Cómo toma el panel

El clúster no es un display Android común: es otra instancia (`fission`) y el panel solo
muestra el display virtual (`fission_bg_xdjaVirtualSurface`, 1920×720) **mientras el
AutoContainer está en modo proyección**. Ese modo se pide por binder, tipo 1000:

| cmd | efecto |
|---|---|
| 16 | proyecto a pantalla completa |
| 17 | mitad |
| 18 | vuelvo al panel original de BYD |

La skin es una ventana `TYPE_APPLICATION_OVERLAY` creada con el contexto del display del
panel. El app manda el cmd 16 al crear la ventana, en `onResume` y cada 5 s; así el panel
vuelve solo aunque otro app (EvSound, Electro, Overdrive) lo devuelva a BYD.

Para inspeccionar el panel desde la PC:

```bash
adb -s <IP>:5555 exec-out fission_screencap -d 0 > panel.raw   # 1920×720 RGBA + cabecera de 8 bytes
adb -s <IP>:5555 exec-out screencap -p > central.png           # pantalla central
```

## Poner tu tema en el EvSound que ya tenés

```bash
cp -r themes/dash/shark themes/dash/mitema
ANDROID_BUILD_TOOLS=/ruta/build-tools ./tools/evsound-build.sh themes
```

(usa `tools/pack.py` + `tools/register.py`; ver `docs/EvSound-engenharia.md`)

## Aviso

Código propio, escrito desde cero a partir del comportamiento observable del panel (contrato
de datos documentado en `docs/`). El repo **no incluye** el APK de EvSound, sus assets ni su
código. Los temas son de quien los escribe.
