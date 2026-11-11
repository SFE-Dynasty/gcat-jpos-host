FROM maven:3.9.6-eclipse-temurin-11 AS build
WORKDIR /app

# 1. Copy POM and download dependencies (cached)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 2. Accept the build arg to force a fresh compile
ARG CACHE_BUST=20261108d

# 3. Copy source code and build
COPY src ./src
RUN echo "Compiling source (Cache bust: $CACHE_BUST)" && \
    mvn clean package -DskipTests -B

FROM eclipse-temurin:11-jre-alpine
WORKDIR /app

# 4. Copy the built JAR
COPY --from=build /app/target/gcat-jpos-host-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
