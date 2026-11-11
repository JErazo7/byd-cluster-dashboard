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

## Quem pode falar com o AutoContainer (verificado)

`/system/etc/container_comm_cfg.json` lista os pacotes autorizados; nesta unidade só
`com.xdja.clusterdemo` (tipos 0–134217727). Qualquer outro nome recebe
`SecurityException: Not allowed package` e o painel continua na BYD. Por isso o build da
SharkLab sai com `--rename-manifest-package com.xdja.clusterdemo`.

O app (uid de aplicação, sem adb) chama `sendInfo(1000, cmd, "")` por binder e o comando é
aceito. O `EvSound` faz o mesmo por ADB (`service call AutoContainer ...`) porque foi
instalado com outro nome; não é necessário para nós.

## O que cada comando faz no painel

| cmd | efeito observado |
|---|---|
| 16 | painel mostra o display virtual `fission_bg_xdjaVirtualSurface` (1920×720) a tela cheia |
| 17 | metade |
| 18 | volta para a interface original da BYD |
| 35 | aceito, sem mudança visível quando o painel já está no modo 16 |

O modo 16 fica ativo até alguém mandar 18. Enquanto ele está ativo, **qualquer** janela no
display 1 aparece no painel — inclusive a overlay `TYPE_APPLICATION_OVERLAY` com título
"EvSound painel" que a SharkLab cria. Sem o modo 16, essa mesma janela é desenhada mas o
painel não a mostra (é exatamente o sintoma de "depende do EvSound").

Verificação do painel físico sem olhar o carro:

```bash
adb shell fission_screencap -d 0 > panel.raw   # cabeçalho 8 bytes + RGBA 1920×720
```

## Flags da janela que esconde a barra nativa (medido)

A overlay no display 1 precisa das MESMAS flags do EvSound: `FLAG_LAYOUT_NO_LIMITS` e mais
nada. Com `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_FULLSCREEN | FLAG_LAYOUT_IN_SCREEN`
o container compõe a barra nativa da BYD (P/ECO, bateria, autonomia, ODO, ADAS) por cima da
skin. Só com `FLAG_LAYOUT_NO_LIMITS` a tela cheia fica limpa.

Medição (16:54–16:57): com as flags antigas, `cmd 18` e `cmd 16` não mudavam a barra; com
`FLAG_LAYOUT_NO_LIMITS`, `cmd 18` devolve a barra e `cmd 16` a remove, com o EvSound ativo
ou parado. O cmd 35 não altera nada nesse estado.

## O que faz o cluster aceitar o cmd 16 (medido em 08/11)

O cluster só mostra o display 1 a tela cheia sem a barra nativa quando o processo que manda
`sendInfo(1000, 16, "")` mantém uma **projeção de tela viva** (MediaProjection):

- a SharkLab pede o consentimento de captura e cria o display virtual
  `shared_fission_bg_XDJAScreenProjection_0` (1920×720, flags 3);
- com essa projeção registrada, `cmd 16` esconde a barra (P/ECO, bateria, autonomia, ODO);
- sem projeção, o mesmo `16` é aceito (retorna 0) mas o cluster continua no layout nativo.

Verificado com o EvSound **desabilitado** (0 processos):

| ação | painel |
|---|---|
| SharkLab aberto e consentimento aceito | skin completa, sem barra |
| `cmd 18` | barra da BYD volta em ~1 s |
| laço de `cmd 16` a cada 5 s | SharkLab volta sozinho em ≤5 s |

O consentimento do MediaProjection é por processo: ao abrir a SharkLab depois de um boot
aparece o diálogo “Comenzar ahora” uma vez. O EvSound não usa MediaProjection: ele conecta
por ADB Wi-Fi (`127.0.0.1:5555`) com chave RSA persistida (autoriza uma vez) e roda
`service call AutoContainer ...` como shell; o vigilante dele reafirma o painel a cada ~60 s.

Consequência: enquanto o EvSound estiver instalado e for **pausado**, ele manda `46 → 18 → 0`
e o cluster recusa o `16` de outros processos até o EvSound voltar a ativar. Com o EvSound
desabilitado/desinstalado não existe esse bloqueio.

## Painel por ADB (rota final, sem diálogos)

O EvSound não usa MediaProjection: ele conversa com o adbd local (`127.0.0.1:5555`) e manda
`service call AutoContainer ...` como shell. Replicamos o cliente ADB dele:

- chave RSA 2048 persistida; a do APK é a da estação de trabalho (já autorizada na central),
  embutida só no build local (`build/assets/adbkey.pem` / `adbkey.pub`, fora do git);
- assinatura do token: bloco PKCS#1 v1.5 de 256 bytes `00 01 FF..FF 00 || DigestInfo SHA-1
  || token` com `RSA/ECB/NoPadding`. `SHA1withRSA` reassina o token e o adbd recusa;
- socket ADB é mantido aberto e cada comando abre um stream shell nele; `cmd 16` a cada 60 s.

Verificado (EvSound desabilitado): conecta sem prompt, `cmd 16` assume o painel, `cmd 18`
devolve a barra da BYD e o laço de 60 s retoma a skin sozinho.

## Cores no painel: o capture é RGBA, não BGRA

`fission_screencap` devolve 4 bytes por pixel e o cabeçalho de 16 bytes
(`w, h, format, stride`), mas os canais vêm na ordem **RGBA**. Decodificar como BGRA
troca R e B e faz a skin azul (`#0480ff`) aparecer laranja (`#ff8004`) na imagem
analisada — no painel de verdade as cores sempre estiveram certas. Branco, cinza e
magenta não denunciam o engano, o que torna o erro fácil de aceitar.

Antes de mexer em formato de janela (`PixelFormat`) ou em `setLayerType`, decodifique
uma captura de teste com as duas ordens e compare com uma cor conhecida.

## Tomar o painel: a ordem e as três coisas que precisam estar vivas

Medido em 2026-11-10, depois de perder uma sessão inteira achando que era regressão de código.
Não é: é ordem de operações.

1. **Sessão ADB com a chave embutida.** O app fala com `127.0.0.1:5555` usando a chave RSA
   embutida no APK (`AdbShell`). Autorizada uma vez no carro, não pede mais nada. Sem ela o
   `cmd 16` não sai e o painel continua na interface da BYD.
2. **`cmd 16` ANTES da janela.** Quem cria o display do painel é o container, ao receber o 16.
   Se a janela da skin for criada antes, ela vai para um display que ainda não existe e o
   painel fica **preto** (foi exatamente o sintoma: preto com os ícones da BYD por cima).
   `takePanel()` e `PanelBoot` agora mandam 16, esperam ~1,2 s e só então criam a janela.
3. **Um produtor para a superfície.** O container compõe
   `shared_fission_bg_XDJAScreenProjection_0`; se ninguém desenhar nela, preto de novo.
   A rota `auto_container` por JSON foi testada payload por payload e **todos devolvem `-1`**
   (rejeitado), então o produtor é a `VirtualDisplay` de `ClusterProjection`, que exige o token
   de captura (`Mirror.request` → diálogo do sistema, uma vez por processo).

Checklist de diagnóstico quando o painel não aparecer:

```bash
adb -s <ip>:5555 shell dumpsys display | grep -c mDisplayId   # 1 = display do painel não existe
adb -s <ip>:5555 shell service call AutoContainer 2 i32 1000 i32 16 s16 null
adb -s <ip>:5555 exec-out fission_screencap -d 0 > panel.raw   # RGBA, cabeçalho de 16 bytes
```

**Não rode o EvSound junto.** Ele também manda 16/18 no mesmo container e os dois ficam
disputando o painel; quando o EvSound está ativo a nossa skin some. Deixe-o desinstalado ou
parado. Se o painel voltar à interface da BYD do nada, é sinal de que algo mandou 18.

## O display do painel existe só enquanto o container está projetando

Medido em 2026-11-10 com o EvSound **rodando** (pacote `com.mdemetrio.evsoundbyd`, atividade
`.MainActivity`; ele não aparece na gaveta, se abre por `am start`):

```
DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface", 1920x720, density 320, type VIRTUAL,
                  owner com.xdja.containerservice (uid 1000),
                  FLAG_PRESENTATION, mDisplayId=2}
```

Camadas no SurfaceFlinger com ele ativo: `EvSound painel` e `EvSound botão do som`.

Duas consequências:

1. **Antes disso `dumpsys display` mostra um display só.** Era por isso que a janela da skin
   respondia `display do painel nao encontrado`: o display não existe até o container entrar em
   projeção com um cliente pendurado nele.
2. **Com o display vivo a nossa skin pinta por cima normalmente** — verificado: com o EvSound
   aberto, abrir a SharkLab desenha o tema `byd` no painel (acelerador, potência, bateria,
   combustível, autonomia, pressões 2.5/2.6 bar, MONTAÑA, P R N D, ODO).

Ou seja: o `ClusterOverlay` está correto; falta a SharkLab conseguir que o container publique o
display. Enquanto isso não estiver resolvido, a ordem prática é: **EvSound inicia a projeção, a
SharkLab desenha a skin**.

## A sequência do container: 46 e depois 16

Extraído do APK do EvSound (`/tmp/ev330.apk`, strings do dex) e confirmado no carro:

```
service call AutoContainer 2 i32 1000 i32 46 s16 null; sleep 1.2
service call AutoContainer 2 i32 1000 i32 16 s16 null
```

**O 46 é o que cria o display do painel** (`fission_bg_xdjaVirtualSurface`). O 16 sozinho
projeta, mas sem o 46 não existe display nenhum e a janela da skin responde "display do painel
nao encontrado". Ao devolver o painel a BYD o EvSound manda **46, 18 e 0**, nessa ordem.

Medido com o EvSound **desabilitado** (`pm disable-user com.mdemetrio.evsoundbyd`): mandando 46 e
16, `dumpsys display` passa de 1 para 2 displays e a skin da SharkLab aparece no painel — já não
depende do EvSound. Falta acertar a escala: a skin sai ampliada e cortada (viewport do painel é
960 CSS px para um viewBox de 1280×480).


## Encendido y apagado del carro (medido 2026-11-10)

| Lectura | Encendido | Apagado (puerta abierta) |
|---|---|---|
| `power.getPowerCtlStatus(3)` | **1** | **0** |
| `power.getShutdownInfo(1)` | 0 | 0 |
| `bodywork.getAutoSystemState` | 0 | 0 |
| `bodywork.getPowerLevel` | 2 | 0 |
| `power.getMcuStatus` | 1 | 1 |
| `energy.getEnergyState` | 0/1 | 0 |

`AbsBYDAutoPowerListener.onPowerCtlStatusChanged(int, int)` publica ese mismo valor.
`getPowerCtlStatus` sólo acepta el índice 3; los demás devuelven `-2147482645`.

Regla de la app: `pc == 1` → aplicar; `pc == 0` → `PanelRelease.release()` (el panel vuelve a BYD
y queda sólo la alerta de puerta). Se consulta cada 2 s.

`ACTION_SCREEN_OFF` y `ACTION_SHUTDOWN` **no llegan** al apagarse: la IVI no los emite.
