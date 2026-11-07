# SharkLab

Panel y sonido de motor para el BYD Shark, con **código propio**: skins en HTML/SVG y un
sintetizador de motor en tiempo real que se modifica editando texto, sin recompilar nada.

El repo tiene dos partes:

| Carpeta | Qué es | Estado |
|---|---|---|
| `engine/` | Sintetizador de motor + modelo de transmisión + 5 perfiles + página de prueba | **funcionando y verificado** |
| `app/` | SharkLab: app Android que pinta la skin y suena (APK 36 KB, sin Gradle) | **compila y firma** |
| `themes/` | Tema `shark` (panel) + preview con sliders | funcionando |
| `tools/` | Reempaquetador para meter tus temas en el EvSound que ya compraste | funcionando |

## Estado real de las funcionalidades

| Función | Estado |
|---|---|
| Panel/skin propio (SVG, 1280×480, claro/oscuro, panel entero, ancho variable) | ✅ |
| Sonido de motor en tiempo real (giro, pedal, carga, turbo, estouros) | ✅ |
| Perfiles de motor editables en texto (`engine/profiles.js`) | ✅ |
| Exportar WAV de cualquier perfil | ✅ |
| Carga de skins desde el almacenamiento (sin recompilar) | ✅ |
| Tarjeta de control dentro del panel (motor, skin, volumen) | ✅ |
| Sonidos propios sin recompilar (`<almacenamiento>/sound/profiles.js`) | ✅ |
| Lectura de datos reales del BYD (CAN/DiLink) | ⏳ pendiente |
| Proyección al panel detrás del volante | ⏳ pendiente |
| Cámaras, ADAS, mapa, Android Auto / CarPlay | ⏳ pendiente (AA/CarPlay requieren licencia de Google/Apple) |

## Compilar la app

```bash
SDK=/opt/android-sdk ./app/build.sh      # sale en app/out/sharklab.apk
```

Sin Gradle: `aapt2` + `javac` + `d8` + `apksigner`. Requisitos: JDK 17, Android SDK
(platform + build-tools), `keytool`.

La app carga `assets/dash/<id>/index.html`, le inyecta `engine/*.js` + `lab/*.js` y alimenta
la skin con datos simulados (`Lab.state()`), igual que el feed de referencia. El sonido
arranca al primer toque (WebView exige gesto).

## Probar el sonido

Abrí `engine/sound.html` en un navegador: elegí motor, mantené apretado el pedal o usá
`AUTO`; `VERIFICAR` renderiza cada motor y compara la frecuencia de explosiones medida con
la teórica; `BAIXAR WAV` exporta 6 segundos de ciclo de conducción.

Última verificación (3000 rpm, 2 s): error de frecuencia < 0.5 % en los 5 motores, sin
clipping (peak < 0.5, RMS 0.11–0.23).

## Modificar un sonido

Todo está en `engine/profiles.js`, con los campos documentados arriba de la lista:

```js
{ id: 'v8_muscle', cylinders: 8, idle: 620, redline: 6200,
  harmonics: { 1: 1.0, 2: 0.72, 3: 0.5 },   // TIMBRE: graves = encorpado, agudos = gritado
  exhaustCut: [95, 620],                     // apertura del escape (Hz) en ralentí / corte
  intake: [0.01, 0.1], turbo: 0, pops: 0.85, // admisión, turbo, estouros
  gears: [3.2, 2.1, 1.5, 1.15, 0.92, 0.75], finalDrive: 3.7 }
```

Copiá el bloque, cambiá números, guardá, recargá la página.

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
