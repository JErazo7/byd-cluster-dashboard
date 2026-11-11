#!/usr/bin/env bash
# Mede os quadros que a camada do painel REALMENTE apresenta (fps e engasgo), lendo os
# timestamps do SurfaceFlinger. É a única medida honesta de fluidez: a página do painel é uma
# janela virtual, então o requestAnimationFrame dela é congelado e não serve de termômetro.
#
#   ./tools/panel-fps.sh [serial]
set -uo pipefail
D="${1:-127.0.0.1:5555}"
echo "aparelho: $D"

layer=$(adb -s "$D" shell "dumpsys SurfaceFlinger --list" 2>/dev/null | tr -d '\r' \
        | grep -i "EvSound painel" | head -1)
if [ -z "$layer" ]; then
  layer=$(adb -s "$D" shell "dumpsys SurfaceFlinger --list" 2>/dev/null | tr -d '\r' \
          | grep -i -E "painel|cluster|sharklab" | grep -v -i "bot.o" | head -1)
fi
if [ -z "$layer" ]; then
  echo "camada do painel nao encontrada. camadas de agora:"
  adb -s "$D" shell "dumpsys SurfaceFlinger --list" 2>/dev/null | tr -d '\r' | head -25
  exit 1
fi
echo "camada: $layer"

adb -s "$D" shell "dumpsys SurfaceFlinger --latency '$layer'" 2>/dev/null | tr -d '\r' \
  | awk '
    NR == 1 { period = $1; next }          # periodo de vsync em ns
    NF == 3 && $2 > 0 {                    # $2 = actualPresentTime do quadro entregue
      if (first == "") first = $2;
      else {
        gap = ($2 - last) / period;        # intervalo desde o quadro anterior, em vsyncs
        if (gap > 1.5) { jank++; lost += gap - 1 }   # 2 vsyncs = um quadro pulado
        if (gap > worst) worst = gap;
      }
      last = $2;
      n++;
    }
    END {
      if (n < 2) { print "sem quadros entregues na amostra"; exit 1 }
      span = (last - first) / 1e9;
      printf "quadros: %d em %.2f s\n", n, span;
      printf "fps: %.1f  (vsync %.1f Hz)\n", (n - 1) / span, 1e9 / period;
      printf "engasgos (>1,5 vsync): %d de %d  (%.0f%%)\n", jank, n, 100 * jank / n;
      printf "quadros pulados: ~%d\n", lost;
      printf "pior intervalo: %.1f vsync\n", worst;
    }'
