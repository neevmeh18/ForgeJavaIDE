

ARG JAVA_VERSION=21
ARG NODE_VERSION=22

FROM node:${NODE_VERSION}-alpine AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/tsconfig.json frontend/vite.config.ts frontend/index.html ./
COPY frontend/src ./src
RUN npm run build

FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS backend
WORKDIR /build
COPY pom.xml ./
COPY backend/forge-core/pom.xml backend/forge-core/
COPY backend/forge-app/pom.xml backend/forge-app/
COPY backend/forge-ext-demo/pom.xml backend/forge-ext-demo/
RUN mvn -B -q -Dmaven.test.skip=true dependency:go-offline
COPY backend ./backend
RUN mvn -B -q package

FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates nodejs npm openssh-client sshpass \
    && rm -rf /var/lib/apt/lists/*

RUN userdel --remove ubuntu 2>/dev/null || true; \
    groupdel ubuntu 2>/dev/null || true; \
    groupadd --gid 1000 forge \
    && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash forge

WORKDIR /app
COPY --from=backend --chown=forge:forge /build/backend/forge-app/target/forge-app.jar /app/forge-app.jar
COPY --from=backend --chown=forge:forge /build/backend/forge-app/target/lib /app/lib
COPY --from=backend --chown=forge:forge /build/backend/forge-ext-demo/target/forge-ext-demo-*.jar /app/extensions/
COPY --from=frontend --chown=forge:forge /build/dist /app/web

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

HEALTHCHECK --interval=10s --timeout=3s --start-period=25s --retries=10 \
    CMD curl -fsS "http://127.0.0.1:${IDE_PORT}/api/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/forge-app.jar"]
