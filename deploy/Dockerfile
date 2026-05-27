# syntax=docker/dockerfile:1.7
#
# Multi-stage build of the amr-geo-failover Spring Boot app.
#
# Builder:  Microsoft Build of OpenJDK 21 on Ubuntu, with apt-installed Maven.
#           Build inside the container so `az acr build` works without a
#           prebuilt jar in the build context.
# Runtime:  Microsoft Build of OpenJDK 21 distroless. Non-root by default
#           (nonroot:nonroot), no shell or package manager, minimal CVE
#           surface. Just the JRE and the fat jar.
#
# Build context expected: repository root. Example:
#   az acr build -r $ACR_NAME -t amr-geo-failover:$TAG \
#                -f deploy/Dockerfile .

# ---- Builder ----------------------------------------------------------------
FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS builder

ENV DEBIAN_FRONTEND=noninteractive \
    MAVEN_OPTS="-Dmaven.repo.local=/root/.m2/repository -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"

RUN apt-get update \
 && apt-get install -y --no-install-recommends maven ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Cache dependencies in a separate layer.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests package \
 && cp target/amr-geo-failover-*.jar /workspace/app.jar

# ---- Runtime ----------------------------------------------------------------
FROM mcr.microsoft.com/openjdk/jdk:21-distroless

# The distroless image runs as `nonroot` (uid 65532) by default. We rely on
# that — no USER directive needed.
WORKDIR /app
COPY --from=builder /workspace/app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
