# receiver

Porta de entrada HTTP do motor (spec 001): valida o contrato, limita por origem, barra retries óbvios no Redis e publica o comando no Kafka antes de responder `202`. Porta 8081.

## Pré-requisitos
Java 21, Docker (os testes de integração usam Testcontainers) e os artefatos `motor-fraude-parent`, `contracts` e `platform-commons` instalados (`./mvnw install` em cada um, nessa ordem, ou token do GitHub Packages).

## Build e testes
```bash
./mvnw verify                 # 63 unitários + 19 de integração
./mvnw -DskipTests package    # gera target/receiver-0.1.0.jar
docker build -t motor-fraude/receiver .
```
Para subir com Kafka, Redis e observabilidade, use o `docker-compose.yml` do repositório https://github.com/luizavramos/motor-fraude (perfil `app`).
