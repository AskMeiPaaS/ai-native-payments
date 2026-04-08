#!/bin/sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if command -v rg >/dev/null 2>&1; then
  list_files() {
    rg -l --glob 'src/**/*.{ts,tsx}' "$1" || true
  }

  has_pattern() {
    rg -q "$1" "$2"
  }
else
  list_files() {
    find src -type f \( -name '*.ts' -o -name '*.tsx' \) -print0 \
      | xargs -0 grep -El "$1" || true
  }

  has_pattern() {
    grep -Eq "$1" "$2"
  }
fi

STATUS=0

check_pairs() {
  pattern="$1"
  cleanup="$2"
  label="$3"

  files=$(list_files "$pattern")
  if [ -z "$files" ]; then
    return
  fi

  for f in $files; do
    if ! has_pattern "$cleanup" "$f"; then
      echo "[leak-check] $label found without $cleanup in: $f"
      STATUS=1
    fi
  done
}

check_pairs 'setInterval\(' 'clearInterval\(' 'Potential interval leak'
check_pairs 'setTimeout\(' 'clearTimeout\(' 'Potential timeout leak'
check_pairs 'addEventListener\(' 'removeEventListener\(' 'Potential listener leak'

if [ "$STATUS" -ne 0 ]; then
  echo "[leak-check] FAILED"
  exit 1
fi

echo "[leak-check] PASSED"
