# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl ocean-bootstrap -am clean package -DskipTests

FROM eclipse-temurin:21-jre

RUN groupadd --system ocean && useradd --system --gid ocean --home-dir /app ocean
WORKDIR /app

COPY --from=builder /workspace/ocean-bootstrap/target/ocean-bootstrap-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --from=builder /workspace/db/migration /app/db/migration
COPY --from=builder /workspace/ocean-business-geo/tools/mago/mago-3d-terrainer-1.14.2-release.jar /app/runtime/mago/mago-3d-terrainer-1.14.2-release.jar

RUN mkdir -p /app/data /app/logs && chown -R ocean:ocean /app

USER ocean
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

