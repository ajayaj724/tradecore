# Base tags pinned to exact releases (verified via docker manifest inspect, 2026-07-07):
# brief's floating tags maven:3-eclipse-temurin-25 / eclipse-temurin:25-jre both exist, but the
# fully-qualified variants below are digest-stable across upstream rebuilds.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml ./
COPY .mvn .mvn
RUN mvn -B dependency:go-offline
COPY src src
COPY config config
# -DskipTests is image assembly only — the full gate (tests included) runs in the CI build job,
# and the image job declares `needs: build` so it can never ship an unverified commit.
RUN mvn -B -DskipTests package spring-boot:repackage

FROM eclipse-temurin:25.0.3_9-jre-noble
RUN useradd --system --uid 1001 tradecore
USER 1001
WORKDIR /app
COPY --from=build /app/target/tradecore-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
