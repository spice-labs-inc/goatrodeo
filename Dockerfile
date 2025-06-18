FROM eclipse-temurin:21-jre-ubi9-minimal

LABEL org.opencontainers.image.source="https://github.com/spice-labs-inc/goatrodeo"
LABEL maintainer="ext-engineering@spicelabs.io"

RUN id -u goat 2>/dev/null || \
    (getent group 0 || groupadd -g 0 root) && \
    useradd --system --create-home --uid 1001 --gid 0 goat

WORKDIR /app
COPY goatrodeo-fat.jar /app/goatrodeo.jar

USER goat:root
ENTRYPOINT ["java", "-jar", "/app/goatrodeo.jar"]
