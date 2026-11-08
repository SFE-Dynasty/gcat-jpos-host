FROM maven:3.9.6-eclipse-temurin-11 AS build
WORKDIR /app
# Copy pom first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B
# Copy source — any source change invalidates from here down
COPY src ./src
# CACHE_BUST after src copy: forces recompile whenever bumped
ARG CACHE_BUST<26261108c
RUN echo "Build timestamp: $CACHE_BUST" > /app/build-info.txt && \
    mvn clean package -DskipTests -B && \
    echo "Build complete: $(ls -la target/*.jar)"

FROM eclipse-temurin:11-jre-alpine
WORKDIR /app
COPY --from=build /app/target/gcat-jpos-host-1.0.0.jar app.jar
COPY --from=build /app/build-info.txt build-info.txt
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
