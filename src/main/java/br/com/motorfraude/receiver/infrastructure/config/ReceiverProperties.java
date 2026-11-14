package br.com.motorfraude.receiver.infrastructure.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "receiver")
public record ReceiverProperties(List<String> clients, long rateLimitPerSecond, Kafka kafka, Redis redis) {

    public record Kafka(String bootstrapServers, String topic, Duration publishTimeout) {}

    public record Redis(String host, int port, Duration commandTimeout, Duration pendingTtl, Duration doneTtl) {}
}
