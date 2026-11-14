# Imagem de execução do receiver. G1 explícito: em container com menos de ~1,8 GB a JVM escolhe o Serial GC sozinha,
# cujas pausas de segundos derrubam a latência (medido: pausa de 3 s a 500 TPS). O jar é construído fora, para a imagem ser pequena e o build ser rápido:
#   ./mvnw -DskipTests package
#   docker build -t motor-fraude/receiver .  (o docker-compose do repositório motor-fraude usa esta pasta como contexto)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/receiver-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
