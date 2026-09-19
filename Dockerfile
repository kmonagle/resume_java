# Why this file exists: Render has no native Java runtime, so this service deploys as a Docker
# image, like every backend in this project.
#
# JS/TS vs Java: there are FOUR stages built from one file.
#   deps    - download the Maven dependencies, so that (slow) step is cached until pom.xml changes,
#             not re-run on every code edit (the analogue of copying package.json before `npm ci`)
#   test    - compile and run the unit tests (`docker build --target test`)
#   build   - compile and package the app into ONE runnable "fat jar" (the app plus all of its
#             dependencies, in a single file)
#   runtime - the small image Render runs: a Java runtime (JRE, no compiler) plus that jar (the
#             last stage, so a plain `docker build .` produces it)
# Unlike Go there is no single static binary: the image carries the JVM. Unlike Node it carries no
# node_modules and no source.
FROM maven:3-eclipse-temurin-25 AS deps
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

FROM deps AS test
COPY src ./src
RUN mvn -B test

FROM deps AS build
COPY src ./src
RUN mvn -B -q -DskipTests package

# The Alpine variant of the Java runtime is about a third of the size of the default one.
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /src/target/links-0.0.1-SNAPSHOT.jar app.jar
# Run as an unprivileged user: if the app were ever compromised, it can't write to the system.
RUN adduser -S -H app
USER app
# Render's free instances have 512 MB. Let the JVM use up to 70% of the container's memory for
# its heap (the default is only 25%), and use the simple single-threaded garbage collector, which
# has the smallest footprint on a small container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
