# The Kotlin API server as a container (Render, or any Docker host).
# Build from the REPO ROOT, because the server compiles the app's shared contract sources
# (apps/android/core/model and core/domain):
#   docker build -f apps/server/Dockerfile -t timeshealth-server .
# Configuration is environment variables only (see README "Settings"): DATABASE_URL,
# NODE_UPSTREAM_URL, ADMIN_*, the signing secrets, PORT (set by Render).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY apps/server/gradlew apps/server/settings.gradle.kts apps/server/build.gradle.kts apps/server/gradle.properties apps/server/
COPY apps/server/gradle apps/server/gradle
WORKDIR /src/apps/server
# Download Gradle and dependencies first, so code changes reuse this layer.
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null || true
WORKDIR /src
COPY apps/android/core/model/src/main apps/android/core/model/src/main
COPY apps/android/core/domain/src/main apps/android/core/domain/src/main
COPY apps/server/src/main apps/server/src/main
WORKDIR /src/apps/server
RUN ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1g -Pkotlin.compiler.execution.strategy=in-process bootJar \
    && cp "$(ls build/libs/*.jar | grep -v plain | head -1)" /src/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/app.jar app.jar
# Sized for a 512 MB instance (Render free).
ENV JAVA_TOOL_OPTIONS="-Xmx300m -Xss512k -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 4000
CMD ["java", "-jar", "app.jar"]
