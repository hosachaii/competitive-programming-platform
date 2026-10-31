#!/usr/bin/env bash
# Regenerate all report diagrams from their Graphviz sources into ../images/.
# Usage: cd report/diagrams && ./render.sh
set -e
cd "$(dirname "$0")"
OUT=../images
DPI=150
for f in architecture er_diagram jwt_auth_flow judge_lifecycle deployment; do
  echo "rendering $f.dot -> $OUT/$f.png"
  dot -Tpng -Gdpi=$DPI "$f.dot" -o "$OUT/$f.png"
done
echo "done."
