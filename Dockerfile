FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
ENV MAVEN_OPTS="-Xmx384m"
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
# deno: yt-dlp needs a JavaScript runtime to solve YouTube's challenges
RUN apk add --no-cache ffmpeg python3 py3-mutagen py3-pip deno curl \
    && pip install --no-cache-dir --break-system-packages gallery-dl \
    && addgroup -S -g 10001 app && adduser -S -u 10001 -G app app \
    && mkdir -p /opt/tools /data && chown app:app /opt/tools /data
# in a directory the service owns: yt-dlp replaces itself on `yt-dlp -U`
RUN curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /opt/tools/yt-dlp \
    && chmod 755 /opt/tools/yt-dlp && chown app:app /opt/tools/yt-dlp
COPY --chown=app:app tools/media-collage.py /opt/tools/media-collage.py
COPY --from=build /app.jar /app/app.jar
USER app
ENV PATH="/opt/tools:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1" \
    DELTA_ROOT=/data \
    DELTA_SELF_UPDATE=true
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
  CMD wget -qO- http://127.0.0.1:${DELTA_MANAGEMENT_PORT:-8080}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
