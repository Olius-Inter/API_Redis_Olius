# Build stage: compiles the JAR with the project's Maven Wrapper.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /code
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home --shell /usr/sbin/nologin olius
COPY --from=build /code/target/olius-redis-api-*.jar app.jar
USER olius
# Hosting platforms may override the port with PORT.
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
