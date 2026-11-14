package br.com.motorfraude.receiver.infrastructure.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do receiver, toda sobrescrevível por variável de ambiente (ex.: {@code RECEIVER_KAFKA_BOOTSTRAP_SERVERS},
 * {@code RECEIVER_REDIS_HOST}, {@code RECEIVER_CLIENTS}). Valores locais em {@code application.yml}.
 *
 * @param clients origens autorizadas (FR-007a)
 * @param rateLimitPerSecond requisições por segundo por origem e por instância (padrão 20.000; env {@code RECEIVER_RATE_LIMIT_PER_SECOND})
 */
@ConfigurationProperties(prefix = "receiver")
public record ReceiverProperties(List<String> clients, long rateLimitPerSecond, Kafka kafka, Redis redis) {

    /**
     * @param bootstrapServers brokers
     * @param topic tópico de comandos
     * @param publishTimeout quanto esperar o ack do Kafka antes de responder 503
     */
    public record Kafka(String bootstrapServers, String topic, Duration publishTimeout) {}

    /**
     * @param commandTimeout prazo de cada comando; acima disso o Redis é tratado como indisponível (5 ms na spec)
     * @param pendingTtl validade da reserva enquanto a publicação não foi confirmada (maior que o delivery.timeout do Kafka)
     * @param doneTtl validade da marca "já aceita" depois do ack (janela de bloqueio de retries)
     */
    public record Redis(String host, int port, Duration commandTimeout, Duration pendingTtl, Duration doneTtl) {}
}
