# SharkLab

Panel propio para el BYD Shark, con **código propio**: skins en HTML/SVG que se editan como
texto y una app Android que las pinta en la pantalla central y en el panel detrás del volante,
**sin depender del EvSound**.

| Carpeta | Qué es | Estado |
|---|---|---|
| `app/` | SharkLab: app Android que pinta la skin (APK 36 KB, sin Gradle) | **compila y firma** |
| `themes/` | Temas `shark`, `sharkmap`, `muscle`, `industria`, `byd` + preview con sliders | funcionando |
| `tools/` | Reempaquetador para meter tus temas en el EvSound que ya compraste | funcionando |

**Grelha das skins:** todas desenham em **1280×480** (a mesma do `shark`): o SVG leva
`width/height="100%"` com `viewBox="0 0 1280 480"` e escala sozinho para o quadro do painel.
Os tamanhos de referência — para as skins novas não ficarem sobredimensionadas — são os do
`shark`: aros de raio 108 com traço 12 e centros em x 262/1018, y 250; número do mostrador 58
(herói 128 no centro), título 19–20, P R N D 26, READY 17, rodapé e odômetro 17, relógio 24,
faixa das luzes da BYD em x 427–853, y 0–54.

O `byd` é o único tema com foto, tudo em `themes/dash/byd/assets/`: `bg.jpg` (paisagem),
`bg-sport.jpg` (estrada em brasa, modo SPORT), `bg-mountain.jpg` (serra verde, modo
MONTAÑA), `bg-mud.jpg` (estrada na selva, modo LODO), `bg-snow.jpg` (serra nevada com aurora, modo
NIEVE), `bg-sand.jpg` (deserto com dunas, modo ARENA), `truck.png`
(caminhonete recortada com alfa) e `tyre.jpg` (carro visto de cima no cartão de pressão,
composto com `mix-blend-mode: screen`).

## Estado real de las funcionalidades

| Función | Estado |
|---|---|
| Panel/skin propio (SVG, 1920×720, claro/oscuro, panel entero, ancho variable) | ✅ |
| Skin en el panel detrás del volante a pantalla completa, sin depender del EvSound | ✅ |
| El app toma el panel solo: manda `AutoContainer` cmd 16 al abrir la ventana y al volver a primer plano | ✅ |
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
| 16 | proyección: el panel empieza a mostrar el display virtual |
| 17 | mitad |
| 18 | vuelvo al panel original de BYD |
| 45 | tela cheia: el container deja de dibujar sus widgets (P/NORMAL, batería, pneus, ODO) encima |
| 46 | recria el display del panel |
| 0 | suelta |

**Secuencia medida en el carro (2026-11-10), orden importa:**

| paso | qué hace | efecto |
|---|---|---|
| ventana de la skin primero | overlay `EvSound painel` en el display 2 | sin ella el 45 no tiene qué mostrar |
| `16` | proyección | la skin aparece con los widgets nativos encima |
| `45` | tela cheia | los widgets nativos desaparecen; queda solo la skin |

`46` **no** va en esa secuencia: recria el display y deja la ventana huérfana, y el panel
vuelve al layout nativo. Solo se usa al soltar (`46` → `18` → `0`) y como rescate cuando la
ventana no se puede crear porque el display no existe.

El lazo de 60 s reafirma `16` + `45` (nunca `46`), así que el panel aguanta aunque otro app
mande `18`. Verificado: 3 minutos en pantalla completa sin reenviar comandos a mano.

La skin es una ventana `TYPE_APPLICATION_OVERLAY` creada con el contexto del display del
panel. El app manda el cmd 16 al crear la ventana y en `onResume`; con el EvSound
desinstalado, nada más devuelve el panel a BYD.

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

## Temas do EvSound (uso pessoal, fora do repo)

Os temas pagos do EvSound **não** entram no repositório (APK público redistribuindo assets de
outra licença). Para usá-los só no seu carro:

```bash
./tools/import-evsound-assets.sh /caminho/EvSound.apk [serial]
```

Isso descompacta o APK licenciado e copia `dash/<tema>/` para
`/sdcard/Android/data/com.xdja.clusterdemo/files/dash/`, que a SharkLab carrega. No APK
gratuito só `pilot`, `muscle` e `vision` trazem `index.html`; os outros temas têm apenas a
miniatura — a skin completa vem do pack Pro/motor do EvSound.

## Backup dos temas do EvSound

`themes/evsound/dash/` guarda os assets extraídos do APK licenciado (backup pessoal, repo privado).
Não entram no APK da SharkLab; para instalar no carro use `tools/import-evsound-assets.sh`.

## Arranque

Ao ligar o carro o painel volta ao layout original da BYD. A skin da SharkLab só assume o
painel quando o app é aberto; ao desligar/dormir a central ela solta o painel de novo
(`cmd 18`) e fecha a tela. `autostart` fica desligado por padrão.
