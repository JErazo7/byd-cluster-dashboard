# AutoContainer — o canal entre a IVI e o painel do motorista

Medido nesta unidade (`BYD_AUTO`, DiLink 3.0, Android 10) por sonda de shell (`app_process`).

## Interface (verificada)

```
service list → AutoContainer: [android.os.IAutoContainer]

int getProjectionDisplayInfo(ProjectionDisplayInfoParcel)
int registerCallback(IContainerCallback)
int sendInfo(int type, int cmd, String arg)
int sendInfo2(int type, byte[] flatbuffer)
int sendJson(int type, String json)
```

`ProjectionDisplayInfoParcel` — `{ int width; int height; String name; IBinder surface; }`.
Ler o estado atual devolve `0` com todos os campos vazios (nenhuma projeção registrada).

## O que funciona

`sendInfo(1000, cmd, "")` — o canal de comandos do painel:

| cmd | efeito |
|---|---|
| 16 | espelha a IVI no painel (tela cheia) |
| 17 | metade |
| 18 | desliga o espelho |

Executável por adb: `service call AutoContainer 2 i32 1000 i32 16 s16 ""`.

## O que NÃO funciona daqui

- `sendJson(1000, ...)` devolve `-1` para qualquer payload testado (inclusive
  `{"auto_container":true}` e o nome do display de projeção).
- `registerCallback(...)` com um proxy de `IContainerCallback` **mata o processo** (Killed),
  então o transporte de callback não é acessível de um processo de app/shell comum.
- Criar um `VirtualDisplay` privado com o nome que o compositor procura
  (`shared_fission_bg_XDJAScreenProjection_0`, 1920×720, `FLAG_PRESENTATION`) funciona e
  renderiza a skin perfeitamente (capturável com `screencap -d <id>`), mas o clúster **não
  o exibe** — falta o attach, que é justamente o passo privilegiado.
- `VIRTUAL_DISPLAY_FLAG_PUBLIC` exige `CAPTURE_VIDEO_OUTPUT` (SecurityException).

## Consequência prática

Do que é acessível sem assinatura de plataforma, o clúster só recebe **espelho da IVI**.
Logo: a skin tem de estar na central (sem barras, 1920×720 no topo do quadro) e os ajustes
aparecem por cima quando abertos. Separar "ajustes na central / skin no painel" exige o
attach privilegiado (root, ou app de sistema).
