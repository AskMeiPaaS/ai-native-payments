#!/bin/sh

set -eu

TARGET_DIR="src/main/resources/qe-native"
DOWNLOAD_URL="${MONGODB_QE_CRYPT_SHARED_LIB_URL:-${QE_IT_CRYPT_SHARED_LIB_URL:-}}"
DOWNLOAD_SHA256="${MONGODB_QE_CRYPT_SHARED_LIB_SHA256:-${QE_IT_CRYPT_SHARED_LIB_SHA256:-}}"

if [ -z "$DOWNLOAD_URL" ]; then
  echo "[download-qe-lib] Set MONGODB_QE_CRYPT_SHARED_LIB_URL (or QE_IT_CRYPT_SHARED_LIB_URL)"
  exit 1
fi

TMP_DIR=$(mktemp -d)
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$TARGET_DIR"

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
