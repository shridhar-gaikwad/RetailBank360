# One Dockerfile for every service. The module is selected at build time, so there is a single place
# to change the base image, the JVM flags or the user.
#
# Stage 1 builds the whole reactor. Dependencies are resolved in their own layer first, so editing a
# source file does not re-download the world on the next build.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY retailbank-common/pom.xml retailbank-common/
COPY retailbank-discovery-server/pom.xml retailbank-discovery-server/
COPY retailbank-api-gateway/pom.xml retailbank-api-gateway/
COPY retailbank-auth-service/pom.xml retailbank-auth-service/
COPY retailbank-customer-service/pom.xml retailbank-customer-service/
COPY retailbank-account-service/pom.xml retailbank-account-service/
COPY retailbank-transaction-service/pom.xml retailbank-transaction-service/
COPY retailbank-loan-service/pom.xml retailbank-loan-service/
COPY retailbank-notification-service/pom.xml retailbank-notification-service/
COPY retailbank-audit-service/pom.xml retailbank-audit-service/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY . .
# Tests run in CI, not in the image build: a container build should be reproducible and fast.
RUN mvn -B -q clean package -DskipTests

# Stage 2 carries only a JRE and the one jar this image is for.
FROM eclipse-temurin:17-jre-alpine
ARG MODULE
ENV MODULE=${MODULE}

# Runs unprivileged: a compromised service should not be root inside its own container.
RUN addgroup -S retailbank && adduser -S -G retailbank retailbank
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-1.0-SNAPSHOT.jar /app/application.jar
RUN chown -R retailbank:retailbank /app
USER retailbank

# MaxRAMPercentage rather than a fixed heap, so the JVM respects whatever the container is given.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
