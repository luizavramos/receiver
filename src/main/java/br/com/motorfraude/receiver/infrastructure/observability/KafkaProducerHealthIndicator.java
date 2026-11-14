package br.com.motorfraude.receiver.infrastructure.observability;

import org.apache.kafka.clients.producer.Producer;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

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
