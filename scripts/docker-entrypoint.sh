#!/bin/sh
# ============================================================================
# Docker Entrypoint for Java AppPayment Service
# Ensures environment variables are properly passed to Spring Boot as system properties
# ============================================================================

set -e

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

# Execute Java application with all options
exec java $JAVA_OPTS $JAVA_OPTS_ENV -jar app.jar
