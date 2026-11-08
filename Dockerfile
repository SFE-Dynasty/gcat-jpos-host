FROM maven:3.9.6-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
ARG CACHE_BUST=26261108b
RUN echo "Cache bust: $CACHE_BUST" && mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:11-jre-alpine
WORKDIR /app
COPY --from=build /app/target/gcat-jpos-host-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
