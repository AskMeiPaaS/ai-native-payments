# Runtime using JRE 21 Debian (glibc required by MongoDB crypt native libs)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Update base packages to patch OS-level vulnerabilities
RUN apt-get update \
	&& apt-get upgrade -y \
	&& rm -rf /var/lib/apt/lists/*

# Copy pre-built JAR from local target directory
COPY target/ai-native-payments-1.0.0.jar app.jar

# Copy optional packaged QE crypt shared library (if bundled at build time)
COPY target/qe-native /app/qe-native

# Copy entrypoint script that properly handles environment variable injection
COPY scripts/docker-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Virtual Threads natively enabled for Java 21
ENV JAVA_OPTS="-Dspring.threads.virtual.enabled=true"

EXPOSE 8080
# Use entrypoint script to ensure environment variables are passed to Spring Boot
ENTRYPOINT ["/entrypoint.sh"]