# ==============================================================================
# Stage 1: Build the Spring Boot fat JAR
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Copy Maven wrapper and POM first (better layer caching — deps change less often)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached unless pom.xml changes)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and build
COPY src/ src/
RUN ./mvnw package -DskipTests -B && \
    mv target/*.jar target/app.jar

# ==============================================================================
# Stage 2: Runtime image
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Install runtime dependencies:
#   tini       — proper PID 1 / signal handling
#   nodejs npm — required for Playwright MCP (npx)
#   git        — used by OpenCode agents for version control
#   curl       — health checks, OpenCode install
#   bash       — scripts / OpenCode shell operations
RUN apk add --no-cache tini nodejs npm git curl bash

# Install OpenCode CLI (installer puts binary in /root/.opencode/bin/)
RUN curl -fsSL https://opencode.ai/install | bash && \
    cp /root/.opencode/bin/opencode /usr/local/bin/opencode && \
    chmod +x /usr/local/bin/opencode

# Create non-root user for running the app
RUN addgroup -S kerenhr && adduser -S kerenhr -G kerenhr

# Create workspace and OpenCode data directories
# Pre-create per-user workspace dirs so bind-mounted config files don't create them as root
RUN mkdir -p /data/workspaces/user1 /data/workspaces/user2 /data/workspaces/atalya \
             /home/kerenhr/.config/opencode /home/kerenhr/.local/share/opencode && \
    chown -R kerenhr:kerenhr /data /home/kerenhr

# Copy the fat JAR from the build stage
COPY --from=build --chown=kerenhr:kerenhr /build/target/app.jar /app/app.jar

# Switch to non-root user
USER kerenhr
WORKDIR /app

# Expose only the Spring Boot port (OpenCode on 4096 stays internal)
EXPOSE 8080

# Use tini as PID 1
ENTRYPOINT ["tini", "--"]

# Start the Spring Boot application (which auto-starts OpenCode via ProcessBuilder)
CMD ["java", "-jar", "app.jar"]
