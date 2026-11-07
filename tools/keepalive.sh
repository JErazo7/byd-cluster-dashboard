#!/system/bin/sh
# SharkLab — mantem a skin projetada no painel do motorista.
#
# Roda como shell (uid 2000) e por isso pode falar com o AutoContainer, coisa que o app
# nao pode. Reaplica o espelho da IVI (cmd 16) a cada 20 s enquanto o nosso app estiver
# rodando — assim o painel volta sozinho depois de trocar de app ou reiniciar o SharkLab.
#
# Instalado por tools/install-keepalive.sh.
# Parar: kill "$(cat /data/local/tmp/sharklab.pid)"

PIDFILE=/data/local/tmp/sharklab.pid
INTERVAL=20

echo $$ > "$PIDFILE"

while true; do
  if [ -n "$(pidof com.sharklab)" ]; then
    service call AutoContainer 2 i32 1000 i32 16 s16 "" >/dev/null 2>&1
  fi
  sleep $INTERVAL
done
