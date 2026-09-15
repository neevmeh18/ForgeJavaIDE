# syntax=docker/dockerfile:1

# Forge — multi-stage build.
#
# The frontend is compiled to static assets, the backend to a jar, and only the results reach
# the runtime image: no Maven, no npm, no build caches. The runtime is the same application
# assembly a developer runs on the host — Docker supplies the environment, not a different IDE.

# One Java baseline for the whole project; it must match <java.version> in pom.xml.
ARG JAVA_VERSION=21
ARG NODE_VERSION=22

# ---- Frontend build -----------------------------------------------------------------------
FROM node:${NODE_VERSION}-alpine AS frontend
WORKDIR /build
# Dependencies first so a source-only change reuses the cached install layer.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/tsconfig.json frontend/vite.config.ts frontend/index.html ./
COPY frontend/src ./src
RUN npm run build

# ---- Backend build ------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS backend
WORKDIR /build
COPY pom.xml ./
COPY backend/forge-core/pom.xml backend/forge-core/
COPY backend/forge-app/pom.xml backend/forge-app/
COPY backend/forge-ext-demo/pom.xml backend/forge-ext-demo/
RUN mvn -B -q -Dmaven.test.skip=true dependency:go-offline
COPY backend ./backend
RUN mvn -B -q -Dmaven.test.skip=true package

# ---- Runtime ------------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

# git backs the source-control provider; curl is used by the health check. Nothing else is
# added: a build toolchain in the runtime image is attack surface with no purpose.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# A non-root account. The container never needs to be privileged, and running as root would
# mean anything started from a terminal in the IDE runs as root too.
# uid/gid 1000 so a bind-mounted workspace owned by the host's first user stays writable.
# Recent Ubuntu bases already ship a user there; it is removed rather than worked around.
RUN userdel --remove ubuntu 2>/dev/null || true; \
    groupdel ubuntu 2>/dev/null || true; \
    groupadd --gid 1000 forge \
    && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash forge

WORKDIR /app
COPY --from=backend --chown=forge:forge /build/backend/forge-app/target/forge-app.jar /app/forge-app.jar
COPY --from=backend --chown=forge:forge /build/backend/forge-app/target/lib /app/lib
COPY --from=backend --chown=forge:forge /build/backend/forge-ext-demo/target/forge-ext-demo-*.jar /app/extensions/
COPY --from=frontend --chown=forge:forge /build/dist /app/web

# Created here so that a fresh named volume mounted at /data inherits this ownership.
RUN mkdir -p /data /workspace && chown -R forge:forge /data /workspace /app

ENV IDE_PORT=3000 \
    IDE_HOST=0.0.0.0 \
    IDE_WORKSPACE_ROOT=/workspace \
    IDE_DATA_DIR=/data \
    IDE_WEB_ROOT=/app/web \
    IDE_EXTENSIONS_DIR=/app/extensions \
    IDE_SHELL=/bin/bash \
    IDE_LOG_LEVEL=INFO \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

USER forge
EXPOSE 3000

# Readiness, not liveness: /api/health answers 503 until the application can serve requests.
HEALTHCHECK --interval=10s --timeout=3s --start-period=25s --retries=10 \
    CMD curl -fsS "http://127.0.0.1:${IDE_PORT}/api/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/forge-app.jar"]
