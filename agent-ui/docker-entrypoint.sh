#!/bin/sh
# Pass environment variables to Node.js server
export JAVA_BACKEND_URL=${JAVA_BACKEND_URL:-http://api-gateway:8080}
# Node.js 22 defaults requestTimeout to 300 s (5 min), which drops long SSE connections.
# Set to 0 (unlimited) was intended for older Node flags; remove unsupported option.
exec node server.js
