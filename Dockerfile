FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/receiver-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
