# syntax=docker/dockerfile:1

FROM gradle:9.5.1-jdk21 AS builder
WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle

RUN chmod +x gradlew \
    && ./gradlew dependencies --no-daemon

COPY src ./src

RUN ./gradlew bootJar --no-daemon \
    && find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system spring \
    && useradd --system --gid spring --create-home spring

COPY --from=builder /workspace/app.jar ./app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
