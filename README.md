# SharkLab

Panel propio para el BYD Shark, con **código propio**: skins en HTML/SVG que se editan como
texto y una app Android que las pinta en la pantalla central y en el panel detrás del volante,
**sin depender del EvSound**.

| Carpeta | Qué es | Estado |
|---|---|---|
| `app/` | SharkLab: app Android que pinta la skin (APK 36 KB, sin Gradle) | **compila y firma** |
| `themes/` | Tema `byd` (único que la app muestra) | funcionando |
| `tools/` | Reempaquetador para meter tus temas en el EvSound que ya compraste | funcionando |

**Grelha da skin:** o `byd` desenha em **1920×720** com `viewBox` — o quadro exato do painel —
e `width/height="100%"`, então escala sozinho. Aros laterais com raio ~200 e traço 14, centros
em x 300/1620 e y 400; número do mostrador 96 (herói 120 no centro); cartões da faixa de baixo
em y 560–700; faixa das luzes da BYD em x 700–1220, y 0–60.

O cartão de pressão usa `assets/tyre-stage.png` — arte com alfa (1510×897, recortada pela
caixa do alfa) que já traz o carro visto de cima, os quatro aros luminosos nas rodas e os
trilhos laterais. Entra com 112 px de altura (~188 de largura) e os números (2.5 bar) nas pontas dos trilhos,
com folga em relação às bordas do cartão. A arte é **igual em todos os modos** — nenhum filtro
de cena — e a antiga, desenhada em SVG, virou este PNG: mesma leitura, menos vértices.

Os cartões são **vidro transparente**: cada um recorta uma cópia desfocada do fundo (`<use
href="#gBg">` com `feGaussianBlur` σ 14 numa região do tamanho do cartão, então só a área
visível é filtrada). Não há tinta nem sombra interna — o que se vê dentro do cartão é o fundo
borrado —, só o aro claro de 1,1 px, um aro interno tenue, um fio de luz no topo e o brilho
diagonal. O fundo muda com a cena e o vidro acompanha, porque a cópia é viva.

Banda de baixo, três cartões: bateria (14..460), **pressões no meio** (470..810) e modos
(820..1266), todos com o fundo `gCard`. Relógio, odômetro e temperatura continuam nas quinas
do topo. Os aros cresceram de R 120 para R 146 com traço 17 (22 no SPORT), ticks e números
escalados, centro em y 196.

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
| La app muestra solo los temas instalados (hoy: `byd`) en una grilla | ✅ |
| Pantalla única: grilla de temas + APLICAR/DESACTIVAR + toggle iniciar automáticamente | ✅ |
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

## La app (una sola pantalla)

`assets/lab/home.html` es toda la interface:

| elemento | qué hace |
|---|---|
| grilla | una tarjeta por tema instalado (`themes/dash/<id>/index.html`); tocar una la pone en el panel |
| APLICAR / DESACTIVAR | toma el panel (ventana → `16` → `45`) o lo devuelve a BYD (`46` → `18` → `0`) |
| iniciar automáticamente | `bootApply`: si está encendido, al arrancar el auto se aplica el tema; si está apagado, el panel queda con BYD y la skin **no** sube sola |

No hay ajustes, galería, logbook ni menús: se borraron `settings.html`, `skins.html`,
`lab-logbook.js` y `lab-ui.js`, y con ellos los temas `industria`, `muscle`, `shark` y
`sharkmap`.

El panel lee la copia editable en `files/dash/<id>/`; si el APK es más nuevo que esa copia,
`ThemeSync` la reemplaza por la del APK al abrir la app (antes se quedaba vieja para siempre y
el panel seguía dibujando el tema anterior).

El estado "aplicado" es la preferencia `applied`: la pone APLICAR y la limpia DESACTIVAR o el
arranque con el toggle apagado. Abrir la app ya no toma el panel por su cuenta.

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
