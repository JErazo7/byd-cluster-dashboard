#!/usr/bin/env bash
# Measures the frames the panel layer REALLY presents (fps and jank) by reading the
# SurfaceFlinger timestamps. It is the only honest measure of smoothness: the panel page is a
# virtual window, so its requestAnimationFrame is frozen and is useless as a thermometer.
#
#   ./tools/panel-fps.sh [serial]
set -uo pipefail
D="${1:-127.0.0.1:5555}"
echo "device: $D"

layer=$(adb -s "$D" shell "dumpsys SurfaceFlinger --list" 2>/dev/null | tr -d '\r' \
        | grep -i "EvSound painel" | head -1)
if [ -z "$layer" ]; then
  layer=$(adb -s "$D" shell "dumpsys SurfaceFlinger --list" 2>/dev/null | tr -d '\r' \
          | grep -i -E "painel|cluster|sharklab" | grep -v -i "bot.o" | head -1)
fi
if [ -z "$layer" ]; then
  echo "panel layer not found. current layers:"
  adb -s "$D" shell "dumpsys SurfaceFlinger --list" 2>/dev/null | tr -d '\r' | head -25
  exit 1
fi
echo "layer: $layer"

adb -s "$D" shell "dumpsys SurfaceFlinger --latency '$layer'" 2>/dev/null | tr -d '\r' \
  | awk '
    NR == 1 { period = $1; next }          # vsync period in ns
    NF == 3 && $2 > 0 {                    # $2 = actualPresentTime of the delivered frame
      if (first == "") first = $2;
      else {
        gap = ($2 - last) / period;        # interval since the previous frame, in vsyncs
        if (gap > 1.5) { jank++; lost += gap - 1 }   # 2 vsyncs = one skipped frame
        if (gap > worst) worst = gap;
      }
      last = $2;
      n++;
    }
    END {
      if (n < 2) { print "no frames delivered in the sample"; exit 1 }
      span = (last - first) / 1e9;
      printf "frames: %d in %.2f s\n", n, span;
      printf "fps: %.1f  (vsync %.1f Hz)\n", (n - 1) / span, 1e9 / period;
      printf "jank (>1.5 vsync): %d of %d  (%.0f%%)\n", jank, n, 100 * jank / n;
      printf "skipped frames: ~%d\n", lost;
      printf "worst interval: %.1f vsync\n", worst;
    }'
