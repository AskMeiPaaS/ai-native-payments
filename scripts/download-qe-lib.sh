#!/bin/sh
# ============================================================================
# Download MongoDB crypt_shared library for Queryable Encryption.
#
# Auto-detects platform (Linux/macOS, x86_64/aarch64) when no explicit URL is
# provided.  Downloads into lib/qe-native/ so the Dockerfile can COPY it in.
#
# Override with:
#   MONGODB_QE_CRYPT_SHARED_LIB_URL  – full URL to the .tgz / .so / .dylib
#   MONGO_CRYPT_VERSION               – e.g. 8.0.9 (default)
#   QE_LIB_TARGET_DIR                 – output directory (default: lib/qe-native)
# ============================================================================

set -eu

MONGO_CRYPT_VERSION="${MONGO_CRYPT_VERSION:-8.0.9}"
TARGET_DIR="${QE_LIB_TARGET_DIR:-lib/qe-native}"
DOWNLOAD_URL="${MONGODB_QE_CRYPT_SHARED_LIB_URL:-${QE_IT_CRYPT_SHARED_LIB_URL:-}}"
DOWNLOAD_SHA256="${MONGODB_QE_CRYPT_SHARED_LIB_SHA256:-${QE_IT_CRYPT_SHARED_LIB_SHA256:-}}"

# ── Auto-detect platform ────────────────────────────────────────────────
if [ -z "$DOWNLOAD_URL" ]; then
  OS="$(uname -s)"
  ARCH="$(uname -m)"

  case "$OS" in
    Linux)
      case "$ARCH" in
        x86_64)  MONGO_ARCH="x86_64"  ;;
        aarch64) MONGO_ARCH="aarch64" ;;
        *)       echo "[download-qe-lib] Unsupported Linux arch: $ARCH"; exit 1 ;;
      esac
      DOWNLOAD_URL="https://downloads.mongodb.com/linux/mongo_crypt_shared_v1-linux-${MONGO_ARCH}-enterprise-ubuntu2204-${MONGO_CRYPT_VERSION}.tgz"
      ;;
    Darwin)
      DOWNLOAD_URL="https://downloads.mongodb.com/osx/mongo_crypt_shared_v1-macos-arm64-enterprise-${MONGO_CRYPT_VERSION}.tgz"
      ;;
    *)
      echo "[download-qe-lib] Unsupported OS: $OS.  Set MONGODB_QE_CRYPT_SHARED_LIB_URL manually."
      exit 1
      ;;
  esac
fi

TMP_DIR=$(mktemp -d)
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$TARGET_DIR"

# Skip download if we already have the library
EXISTING=$(find "$TARGET_DIR" -maxdepth 1 -name 'mongo_crypt_v1.*' -type f 2>/dev/null | head -n 1)
if [ -n "${EXISTING:-}" ]; then
  echo "[download-qe-lib] Library already present: $EXISTING  (delete to re-download)"
  exit 0
fi

ARTIFACT_PATH="$TMP_DIR/crypt-shared-artifact"
echo "[download-qe-lib] Downloading: $DOWNLOAD_URL"
curl -fsSL "$DOWNLOAD_URL" -o "$ARTIFACT_PATH"

if [ -n "$DOWNLOAD_SHA256" ]; then
  ACTUAL_SHA256=$(sha256sum "$ARTIFACT_PATH" | awk '{print $1}')
  if [ "$ACTUAL_SHA256" != "$DOWNLOAD_SHA256" ]; then
    echo "[download-qe-lib] SHA256 mismatch. expected=$DOWNLOAD_SHA256 actual=$ACTUAL_SHA256"
    exit 1
  fi
fi

LOWER_URL=$(printf "%s" "$DOWNLOAD_URL" | tr '[:upper:]' '[:lower:]')
if printf "%s" "$LOWER_URL" | grep -Eq '\\.(tgz|tar\\.gz)(\\?|$)'; then
  tar -xzf "$ARTIFACT_PATH" -C "$TMP_DIR"
elif printf "%s" "$LOWER_URL" | grep -Eq '\\.(zip)(\\?|$)'; then
  unzip -q "$ARTIFACT_PATH" -d "$TMP_DIR"
else
  URL_PATH=$(printf "%s" "$DOWNLOAD_URL" | sed 's/[?#].*$//')
  URL_BASENAME=$(basename "$URL_PATH")
  case "$URL_BASENAME" in
    mongo_crypt_v1.*|mongo_csfle_v1.*)
      TARGET_NAME="$URL_BASENAME"
      ;;
    *)
      TARGET_NAME="mongo_crypt_v1.so"
      ;;
  esac
  mv "$ARTIFACT_PATH" "$TMP_DIR/$TARGET_NAME"
fi

FOUND=$(find "$TMP_DIR" -type f \( -name 'mongo_crypt_v1.*' -o -name 'mongo_csfle_v1.*' \) | head -n 1)
if [ -z "${FOUND:-}" ]; then
  echo "[download-qe-lib] No mongo_crypt_v1.* file found in downloaded artifact"
  exit 1
fi

EXT="${FOUND##*.}"
TARGET_LIB="$TARGET_DIR/mongo_crypt_v1.$EXT"
cp "$FOUND" "$TARGET_LIB"
chmod 755 "$TARGET_LIB"

echo "[download-qe-lib] Installed $TARGET_LIB"
