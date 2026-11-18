# receiver

Porta de entrada HTTP do motor: valida o contrato, limita por origem, barra retries óbvios no Redis e publica o comando no Kafka antes de responder `202`. Porta 8081.

## Pré-requisitos
Java 21, Docker (os testes de integração usam Testcontainers) e os artefatos `motor-fraude-parent`, `contracts` e `platform-commons` instalados (`./mvnw install` em cada um, nessa ordem, ou token do GitHub Packages).

## Build e testes
```bash
./mvnw verify                 # 63 unitários + 19 de integração
./mvnw -DskipTests package    # gera target/receiver-0.1.0.jar
docker build -t motor-fraude/receiver .
```
Para subir com Kafka, Redis e observabilidade, use o `docker-compose.yml` do repositório https://github.com/luizavramos/fraud-detection-engine (perfil `app`).

## Contrato e configuração

Um único endpoint: `POST /v1/transactions:evaluate`, com o cabeçalho `X-Client-Id` de uma origem permitida (`pix-core`, `ted-core`, `boleto-core` ou `card-core`). Resposta `202` quando o comando foi publicado em `transaction-commands.v1`; request, response e códigos de erro estão em [`docs/endpoints.md`](https://github.com/luizavramos/fraud-detection-engine/blob/main/docs/endpoints.md) do repositório de visão geral. Saúde e métricas: `/actuator/health` e `/actuator/prometheus`.

Os padrões estão em `src/main/resources/application.yml` e qualquer um pode ser trocado por variável de ambiente (por exemplo `RECEIVER_KAFKA_BOOTSTRAP_SERVERS`, `RECEIVER_REDIS_HOST`). Vale destacar:

| Propriedade | Padrão | Para quê |
|---|---|---|
| `receiver.rate-limit-per-second` (`RECEIVER_RATE_LIMIT_PER_SECOND`) | `20000` | limite por origem; acima disso responde `429` |
| `receiver.kafka.bootstrap-servers` | `localhost:9092` | brokers |
| `receiver.redis.host` / `port` | `localhost` / `6379` | Redis da deduplicação de retries |
| `receiver.redis.command-timeout` | `5ms` | se o Redis demorar mais, o receiver segue sem a barreira (o motor ainda deduplica) |
| `receiver.kafka.publish-timeout` | `2500ms` | espera máxima pela confirmação do Kafka antes de falhar a requisição |
