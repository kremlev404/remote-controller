# Runtime image only. deploy.sh builds the relay with Gradle first and puts
# `./gradlew :relay:installDist` output next to this file as ./relay.
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin relay
COPY relay/ /opt/relay/

USER relay
ENV HOST=0.0.0.0 \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["/opt/relay/bin/relay"]
