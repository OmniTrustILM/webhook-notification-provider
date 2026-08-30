# Build stage
FROM maven:3.9.16-eclipse-temurin-21 AS build

COPY src /home/app/src
COPY pom.xml /home/app
COPY settings.xml /root/.m2/settings.xml
COPY docker /home/app/docker

# Tests are not run here: the build and build_pr workflows already run the full suite once on
# native hardware, and this stage is built once per target architecture.
RUN mvn -f /home/app/pom.xml clean package -DskipTests

# Optimize stage
FROM eclipse-temurin:26-jdk-alpine AS optimize

COPY --from=build /home/app/target/*.jar /app/app.jar

WORKDIR /app

# List jar modules
RUN jar xf app.jar
RUN jdeps \
    --ignore-missing-deps \
    --print-module-deps \
    --multi-release 21 \
    --recursive \
    --class-path 'BOOT-INF/lib/*' \
    app.jar > modules.txt

# Create a custom Java runtime
RUN $JAVA_HOME/bin/jlink \
         --add-modules $(cat modules.txt) \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=zip-6 \
         --output /javaruntime

# Package stage
FROM alpine:latest

ENV JAVA_HOME=/opt/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# copy optimized JRE
COPY --from=optimize /javaruntime $JAVA_HOME

LABEL org.opencontainers.image.authors="ILM <ilm@omnitrust.com>"

# Upgrade OS packages to pick up security fixes not yet in the base image
RUN apk update && apk upgrade --no-cache

RUN addgroup --system --gid 10001 webhook-notification-provider && adduser --system --home /opt/webhook-notification-provider --uid 10001 --ingroup webhook-notification-provider webhook-notification-provider

COPY --from=build /home/app/docker /
COPY --from=build /home/app/target/*.jar /opt/webhook-notification-provider/app.jar

WORKDIR /opt/webhook-notification-provider

ENV JDBC_URL=
ENV JDBC_USERNAME=
ENV JDBC_PASSWORD=
ENV DB_SCHEMA=webhooknp
ENV PORT=8080
ENV JAVA_OPTS=

USER 10001

ENTRYPOINT ["/opt/webhook-notification-provider/entry.sh"]
