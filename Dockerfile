# Runtime using lightweight JRE 21 Alpine
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Update Alpine packages to patch OS-level vulnerabilities
RUN apk update && apk upgrade && rm -rf /var/cache/apk/*

# Copy pre-built JAR from local target directory
COPY target/ai-native-payments-1.0.0.jar app.jar

# Copy entrypoint script that properly handles environment variable injection
COPY scripts/docker-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Virtual Threads natively enabled for Java 21
ENV JAVA_OPTS="-Dspring.threads.virtual.enabled=true"

EXPOSE 8080
# Use entrypoint script to ensure environment variables are passed to Spring Boot
ENTRYPOINT ["/entrypoint.sh"]