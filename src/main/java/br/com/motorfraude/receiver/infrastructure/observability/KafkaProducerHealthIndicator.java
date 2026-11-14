package br.com.motorfraude.receiver.infrastructure.observability;

import org.apache.kafka.clients.producer.Producer;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness: o produtor só está pronto se enxerga as partições do tópico de comandos. O Redis fica de fora de propósito:
 * o receiver é fail-open, então Redis fora do ar não é motivo para tirá-lo do balanceador.
 *
 * <p>{@code partitionsFor} usa os metadados em cache; só espera a rede (até {@code max.block.ms}, 1 s) quando ainda não
 * há metadados.
 */
public class KafkaProducerHealthIndicator implements HealthIndicator {

    private final Producer<String, byte[]> producer;
    private final String topic;

    public KafkaProducerHealthIndicator(Producer<String, byte[]> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public Health health() {
        try {
            var partitions = producer.partitionsFor(topic);
            if (partitions == null || partitions.isEmpty()) {
                return Health.down().withDetail("topic", topic).withDetail("error", "tópico sem partições").build();
            }
            return Health.up().withDetail("topic", topic).withDetail("partitions", partitions.size()).build();
        } catch (RuntimeException e) {
            return Health.down()
                    .withDetail("topic", topic)
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
