# SearchForge production image: React UI + Spring Boot API + one immutable index.
#
#   docker build -t searchforge .                       # demo index (built from docs/guide)
#   docker build -t searchforge \
#     --build-arg INDEX_URL=https://github.com/Software-Cookie/SearchForge/releases/download/index-20261201/index.tgz \
#     --build-arg INDEX_SHA256=<sha256 of index.tgz> .  # a real crawl index from a Release

# ---- 1. Build the React UI ----
FROM node:25-alpine AS ui
WORKDIR /ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
RUN npm run build

# ---- 2. Build the API jar (with the UI inside) and the demo index ----
# The official Gradle image avoids downloading Gradle at build time, which is faster and
# works on networks that block GitHub release downloads.
FROM gradle:9.8.2-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY config config
COPY sf-core sf-core
COPY sf-crawler sf-crawler
COPY sf-indexer sf-indexer
COPY sf-query sf-query
COPY sf-api sf-api
COPY sf-bench sf-bench
COPY docs/guide/parts docs/guide/parts
COPY --from=ui /ui/dist ui/dist
# Tests run in CI before an image is ever built; skip them here.
RUN gradle --no-daemon -x test :sf-api:bootJar :sf-indexer:installDist
RUN sf-indexer/build/install/sf-indexer/bin/sf-indexer \
      --markdown docs/guide/parts \
      --base-url https://github.com/Software-Cookie/SearchForge/blob/main/docs/guide/parts/ \
      --out /index-demo

# ---- 3. Pick the index: a verified Release download, or the demo index ----
FROM alpine:3.24 AS index
ARG INDEX_URL=""
ARG INDEX_SHA256=""
COPY --from=build /index-demo /index-demo
RUN set -eu; \
    if [ -n "$INDEX_URL" ]; then \
      [ -n "$INDEX_SHA256" ] || { echo "INDEX_SHA256 is required with INDEX_URL" >&2; exit 1; }; \
      wget -q -O /tmp/index.tgz "$INDEX_URL"; \
      echo "$INDEX_SHA256  /tmp/index.tgz" | sha256sum -c -; \
      mkdir /index && tar -xzf /tmp/index.tgz -C /index; \
    else \
      cp -r /index-demo /index; \
    fi; \
    test -f /index/meta.properties

# ---- 4. Runtime: JRE only, non-root user ----
FROM eclipse-temurin:24-jre
RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin sf
WORKDIR /app
COPY --from=build /src/sf-api/build/libs/sf-api.jar app.jar
COPY --from=index /index /app/index
USER 10001
ENV SEARCHFORGE_INDEX_DIR=/app/index \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
