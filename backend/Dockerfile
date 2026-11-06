# ---- Build stage: compile the Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Cache dependencies first (faster rebuilds)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests clean package

# ---- Run stage: slim JRE with just the jar ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/maillook-0.1.0.jar app.jar
# Force UTF-8 everywhere so Tamil subjects and names survive end-to-end
# (container locale defaults to POSIX/ASCII).
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
# Railway sets $PORT; Spring reads it via application.yml (${PORT:8097})
EXPOSE 8097
# Small-container JVM tuning (same rationale as the Forms and ID Dockerfiles):
# capped heap that returns freed pages to the OS, SerialGC for 1 vCPU.
# Mail sync parses whole messages and attachments, so it gets 512m like Forms.
# No preferIPv6Addresses: Railway private hosts are IPv6-only and connect without it,
# while public egress is IPv4-only (imap/smtp.hostinger.com, the suite's apps).
# Override via JAVA_OPTS in the Railway service variables.
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx512m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dmail.mime.charset=UTF-8"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
