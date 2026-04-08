# Runtime using JRE 21 Debian (glibc required by MongoDB crypt native libs)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Update base packages to patch OS-level vulnerabilities
# curl + file are kept for the crypt_shared download and health checks
RUN apt-get update \
	&& apt-get upgrade -y \
	&& apt-get install -y --no-install-recommends curl file \
	&& rm -rf /var/lib/apt/lists/*

# Copy pre-built JAR from local target directory
COPY target/ai-native-payments-1.0.0.jar app.jar

# ── MongoDB crypt_shared library (Queryable Encryption) ─────────────────────
# Strategy: try downloading the correct platform binary from MongoDB's CDN.
# If the network is unavailable (air-gapped / DNS blocked), fall back to the
# pre-downloaded copy in lib/qe-native/ on the host.
ARG MONGO_CRYPT_VERSION=8.0.9
RUN set -e; \
    mkdir -p /app/qe-native; \
    ARCH=$(dpkg --print-architecture); \
    case "$ARCH" in \
      amd64)  MONGO_ARCH="x86_64"  ;; \
      arm64)  MONGO_ARCH="aarch64" ;; \
      *)      echo "[Dockerfile] Unsupported arch: $ARCH" && exit 1 ;; \
    esac; \
    URL="https://downloads.mongodb.com/linux/mongo_crypt_shared_v1-linux-${MONGO_ARCH}-enterprise-ubuntu2204-${MONGO_CRYPT_VERSION}.tgz"; \
    echo "[Dockerfile] Attempting download: $URL"; \
    if curl -fsSL --connect-timeout 15 --max-time 120 "$URL" -o /tmp/crypt-shared.tgz 2>/dev/null; then \
      tar -xzf /tmp/crypt-shared.tgz --strip-components=1 -C /tmp lib/mongo_crypt_v1.so; \
      mv /tmp/mongo_crypt_v1.so /app/qe-native/mongo_crypt_v1.so; \
      rm -f /tmp/crypt-shared.tgz; \
      echo "[Dockerfile] Downloaded crypt_shared from MongoDB CDN"; \
    else \
      echo "[Dockerfile] Download failed — will use local fallback"; \
    fi

# Fallback: copy the pre-downloaded host-side library (no-op if download succeeded)
COPY lib/qe-native/mongo_crypt_v1.so /tmp/local_mongo_crypt_v1.so
RUN if [ ! -f /app/qe-native/mongo_crypt_v1.so ]; then \
      cp /tmp/local_mongo_crypt_v1.so /app/qe-native/mongo_crypt_v1.so; \
      echo "[Dockerfile] Using local fallback crypt_shared"; \
    fi; \
    chmod 755 /app/qe-native/mongo_crypt_v1.so; \
    rm -f /tmp/local_mongo_crypt_v1.so; \
    file /app/qe-native/mongo_crypt_v1.so

# Copy entrypoint script that properly handles environment variable injection
COPY scripts/docker-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Virtual Threads natively enabled for Java 21
ENV JAVA_OPTS="-Dspring.threads.virtual.enabled=true"

EXPOSE 8080
# Use entrypoint script to ensure environment variables are passed to Spring Boot
ENTRYPOINT ["/entrypoint.sh"]