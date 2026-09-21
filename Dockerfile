# ---- Build stage: compila o Quarkus a partir do código-fonte ----
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Sem unzip o mvnw baixa o .tar.gz do Maven em vez do .zip, e o distributionSha256Sum
# do maven-wrapper.properties (que é do .zip) reprova a validação.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B package -DskipTests

# ---- Runtime stage: fast-jar padrão do Quarkus (target/quarkus-app), sem uber-jar ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /deployments

COPY --from=build /workspace/target/quarkus-app/ ./

# O Render injeta PORT; o application.properties já lê quarkus.http.port=${PORT:8080}.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
