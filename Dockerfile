# Runtime image for Backlog.fyi. It packages an already-built JAR rather than building from source:
# CI builds and tests the JAR once, and the same artifact becomes both the GitHub Release and this
# image. To build an image locally: `./gradlew release && docker build -t taskboard .`
#
# No RUN steps, so the image builds for any platform (amd64 and arm64 are published) without
# emulation. Self-hosting guide: docs/SELF-HOSTING.md.
FROM eclipse-temurin:25-jre

ARG JAR_FILE=build/libs/Taskboard-*-SNAPSHOT.jar
COPY ${JAR_FILE} /app/app.jar

# `container` logs to stdout instead of a file in the working directory (logback-spring.xml).
ENV SPRING_PROFILES_ACTIVE=prod,container

# Any unprivileged uid works: the app writes nothing outside the JVM's temp directory.
USER 10001:10001
WORKDIR /app
EXPOSE 8080

# Size the heap from the container's memory limit; extra JVM flags go in JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
