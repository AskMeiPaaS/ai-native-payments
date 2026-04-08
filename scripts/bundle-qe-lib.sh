#!/bin/sh

set -eu

TARGET_DIR="target/qe-native"
SOURCE_PATH="${QE_IT_CRYPT_SHARED_LIB_PATH:-}"
DOWNLOAD_URL="${QE_IT_CRYPT_SHARED_LIB_URL:-${MONGODB_QE_CRYPT_SHARED_LIB_URL:-}}"
DOWNLOAD_SHA256="${QE_IT_CRYPT_SHARED_LIB_SHA256:-${MONGODB_QE_CRYPT_SHARED_LIB_SHA256:-}}"
DEFAULT_SOURCE_DIR="src/main/resources/qe-native"

read_magic() {
  od -An -tx1 -N4 "$1" 2>/dev/null | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]'
}

validate_binary_for_extension() {
  candidate="$1"
  ext="$2"
  magic=$(read_magic "$candidate")

  case "$ext" in
    so)
      [ "$magic" = "7f454c46" ] || {
        echo "[bundle-qe-lib] Invalid .so binary format (expected ELF) for $candidate"
        exit 1
      }
      ;;
    dylib)
      case "$magic" in
        cffaedfe|cefaedfe|feedfacf|feedface) ;;
        *)
          echo "[bundle-qe-lib] Invalid .dylib binary format (expected Mach-O) for $candidate"
          exit 1
          ;;
      esac
      ;;
  esac
}

mkdir -p "$TARGET_DIR"

TMP_DIR=""
cleanup() {
  if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT

download_library() {
  TMP_DIR=$(mktemp -d)
  ARTIFACT_PATH="$TMP_DIR/crypt-shared-artifact"

  echo "[bundle-qe-lib] Downloading crypt shared library from: $DOWNLOAD_URL"
  curl -fsSL "$DOWNLOAD_URL" -o "$ARTIFACT_PATH"

  if [ -n "$DOWNLOAD_SHA256" ]; then
    ACTUAL_SHA256=$(sha256sum "$ARTIFACT_PATH" | awk '{print $1}')
    if [ "$ACTUAL_SHA256" != "$DOWNLOAD_SHA256" ]; then
      echo "[bundle-qe-lib] SHA256 mismatch. expected=$DOWNLOAD_SHA256 actual=$ACTUAL_SHA256"
      exit 1
    fi
  fi

  LOWER_URL=$(printf "%s" "$DOWNLOAD_URL" | tr '[:upper:]' '[:lower:]')
  if printf "%s" "$LOWER_URL" | grep -Eq '\\.(tgz|tar\\.gz)(\\?|$)'; then
    tar -xzf "$ARTIFACT_PATH" -C "$TMP_DIR"
  elif printf "%s" "$LOWER_URL" | grep -Eq '\\.(zip)(\\?|$)'; then
    unzip -q "$ARTIFACT_PATH" -d "$TMP_DIR"
  else
    # Treat as direct binary download
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

  CANDIDATE=$(find "$TMP_DIR" -type f \( -name 'mongo_crypt_v1.*' -o -name 'mongo_csfle_v1.*' \) | head -n 1)
  if [ -z "${CANDIDATE:-}" ]; then
    echo "[bundle-qe-lib] Download succeeded but no mongo_crypt_v1.* file found"
    exit 1
  fi

  SOURCE_PATH="$CANDIDATE"
}

if [ -z "$SOURCE_PATH" ]; then
  if [ -n "$DOWNLOAD_URL" ]; then
    download_library
  elif [ -d "$DEFAULT_SOURCE_DIR" ]; then
    SOURCE_PATH="$DEFAULT_SOURCE_DIR"
  else
    echo "[bundle-qe-lib] QE_IT_CRYPT_SHARED_LIB_PATH / QE_IT_CRYPT_SHARED_LIB_URL not set; skipping library bundling."
    exit 0
  fi
fi

if [ -d "$SOURCE_PATH" ]; then
  FOUND_LIBS=$(find "$SOURCE_PATH" -maxdepth 1 -name 'mongo_crypt_v1.*' | sort)
  if [ -z "${FOUND_LIBS:-}" ]; then
    echo "[bundle-qe-lib] No mongo_crypt_v1.* files found in directory: $SOURCE_PATH; skipping library bundling."
    exit 0
  fi
  echo "$FOUND_LIBS" | while IFS= read -r file; do
    [ -z "$file" ] && continue
    EXT="${file##*.}"
    validate_binary_for_extension "$file" "$EXT"
    TARGET_LIB="$TARGET_DIR/mongo_crypt_v1.$EXT"
    cp "$file" "$TARGET_LIB"
    chmod 755 "$TARGET_LIB"
    echo "[bundle-qe-lib] Bundled crypt shared library from $file to $TARGET_LIB"
  done
  exit 0
else
  SOURCE_LIB="$SOURCE_PATH"
fi

if [ ! -e "$SOURCE_LIB" ]; then
  echo "[bundle-qe-lib] File not found: $SOURCE_LIB"
  exit 1
fi

SOURCE_BASENAME=$(basename "$SOURCE_LIB")
case "$SOURCE_BASENAME" in
  mongo_crypt_v1.*|mongo_csfle_v1.*)
    ;;
  *)
    echo "[bundle-qe-lib] Invalid file name '$SOURCE_BASENAME'. Expected mongo_crypt_v1.<ext> or mongo_csfle_v1.<ext>"
    exit 1
    ;;
esac

SOURCE_DIR=$(cd "$(dirname "$SOURCE_LIB")" && pwd -P)
SOURCE_REAL="$SOURCE_DIR/$SOURCE_BASENAME"

if [ ! -f "$SOURCE_REAL" ]; then
  echo "[bundle-qe-lib] Resolved file not found: $SOURCE_REAL"
  exit 1
fi

EXT="${SOURCE_BASENAME##*.}"
validate_binary_for_extension "$SOURCE_REAL" "$EXT"
TARGET_LIB="$TARGET_DIR/mongo_crypt_v1.$EXT"

cp "$SOURCE_REAL" "$TARGET_LIB"
chmod 755 "$TARGET_LIB"

echo "[bundle-qe-lib] Bundled crypt shared library from $SOURCE_REAL to $TARGET_LIB"
