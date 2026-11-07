#!/usr/bin/env bash
# Fail when a Scala file outside a fake/ directory uses Thread.sleep.
# Build output (target/) is not scanned.
set -euo pipefail
cd "$(dirname "$0")/.."

hits=$(find . \
  \( -name target -o -name .git -o -name .bsp -o -name .scala-build \) -prune -o \
  -name '*.scala' -not -path '*/fake/*' -type f -print0 |
  xargs -0 -r grep -nE 'Thread\s*\.\s*sleep|Thread\.\{[^}]*sleep|import\s+.*Thread\.(sleep|\{[^}]*sleep)' || true)

if [ -n "$hits" ]; then
  echo "Thread.sleep is banned outside fake/ test helpers:" >&2
  echo "$hits" >&2
  exit 1
fi
echo "No disallowed Thread.sleep found."
