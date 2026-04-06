#!/bin/sh
# Pass environment variables to Node.js server
export JAVA_BACKEND_URL=${JAVA_BACKEND_URL:-http://api-gateway:8080}
# Node.js 22 defaults requestTimeout to 300 s (5 min), which drops long SSE connections.
# Set to 0 (unlimited) so slow Ollama inferences don't get cut off.
exec node --request-timeout=0 server.js
