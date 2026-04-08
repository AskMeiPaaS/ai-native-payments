#!/bin/sh
# ============================================================================
# Docker Entrypoint for Java AppPayment Service
# Ensures environment variables are properly passed to Spring Boot as system properties
# ============================================================================

set -e

read_magic() {
  target_file="$1"
  od -An -tx1 -N4 "$target_file" 2>/dev/null | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]'
}

is_valid_qe_library_for_os() {
  target_file="$1"
  os_name="$2"

  [ -f "$target_file" ] || return 1
  magic=$(read_magic "$target_file")

  case "$os_name" in
    Linux)
      [ "$magic" = "7f454c46" ]
      ;;
    Darwin)
      [ "$magic" = "cffaedfe" ] || [ "$magic" = "cefaedfe" ] || [ "$magic" = "feedfacf" ] || [ "$magic" = "feedface" ]
      ;;
    *)
      return 1
      ;;
  esac
}

# Build Java Options with all environment variables
JAVA_OPTS_ENV=""

# MongoDB Configuration
if [ -n "$PRIMARY_MONGO_URI" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DPRIMARY_MONGO_URI=$PRIMARY_MONGO_URI"
fi
if [ -n "$AUDIT_MONGO_URI" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DAUDIT_MONGO_URI=$AUDIT_MONGO_URI"
fi
if [ -n "$HITL_MONGO_URI" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DHITL_MONGO_URI=$HITL_MONGO_URI"
fi

# AI ModelConfiguration  
if [ -n "$VOYAGE_API_KEY" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DVOYAGE_API_KEY=$VOYAGE_API_KEY"
fi
if [ -n "$EMBEDDING_MODEL_NAME" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DEMBEDDING_MODEL_NAME=$EMBEDDING_MODEL_NAME"
fi
if [ -n "$RERANKER_MODEL_NAME" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DRERANKER_MODEL_NAME=$RERANKER_MODEL_NAME"
fi
if [ -n "$RERANKER_TOP_K" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DRERANKER_TOP_K=$RERANKER_TOP_K"
fi
if [ -n "$LLM_BASE_URL" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DLLM_BASE_URL=$LLM_BASE_URL"
fi
if [ -n "$LLM_MODEL_NAME" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DLLM_MODEL_NAME=$LLM_MODEL_NAME"
fi
if [ -n "$LLM_TEMPERATURE" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DLLM_TEMPERATURE=$LLM_TEMPERATURE"
fi
if [ -n "$LLM_TIMEOUT_SECONDS" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DLLM_TIMEOUT_SECONDS=$LLM_TIMEOUT_SECONDS"
fi
if [ -n "$HTTP_CONNECT_TIMEOUT" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DHTTP_CONNECT_TIMEOUT=$HTTP_CONNECT_TIMEOUT"
fi
if [ -n "$HTTP_REQUEST_TIMEOUT" ]; then
  JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DHTTP_REQUEST_TIMEOUT=$HTTP_REQUEST_TIMEOUT"
fi

# Queryable Encryption crypt shared library path
# Default: look up the library that the Dockerfile downloaded
OS_NAME="$(uname -s)"
if [ -z "$MONGODB_QE_CRYPT_SHARED_LIB_PATH" ]; then
  # Prefer the path that the Dockerfile downloads (always Linux ELF)
  if [ -f /app/qe-native/mongo_crypt_v1.so ]; then
    MONGODB_QE_CRYPT_SHARED_LIB_PATH=/app/qe-native/mongo_crypt_v1.so
  else
    case "$OS_NAME" in
      Darwin)
        [ -f /app/qe-native/mongo_crypt_v1.dylib ] && MONGODB_QE_CRYPT_SHARED_LIB_PATH=/app/qe-native/mongo_crypt_v1.dylib
        ;;
    esac
  fi
fi
if [ -n "$MONGODB_QE_CRYPT_SHARED_LIB_PATH" ]; then
  if is_valid_qe_library_for_os "$MONGODB_QE_CRYPT_SHARED_LIB_PATH" "$OS_NAME"; then
    JAVA_OPTS_ENV="$JAVA_OPTS_ENV -DMONGODB_QE_CRYPT_SHARED_LIB_PATH=$MONGODB_QE_CRYPT_SHARED_LIB_PATH"
  else
    echo "[entrypoint] Ignoring QE library path due to incompatible binary format for $OS_NAME: $MONGODB_QE_CRYPT_SHARED_LIB_PATH"
    unset MONGODB_QE_CRYPT_SHARED_LIB_PATH
  fi
fi

# Execute Java application with all options
exec java $JAVA_OPTS $JAVA_OPTS_ENV -jar app.jar
