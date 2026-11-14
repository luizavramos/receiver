package br.com.motorfraude.receiver.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** T120 — readiness: o produtor precisa ter os metadados do tópico de comandos. O Redis não entra (fail-open). */
class KafkaProducerHealthIndicatorTest {

    private static final String TOPIC = "transaction-commands.v1";

    @SuppressWarnings("unchecked")
    private final Producer<String, byte[]> producer = mock(Producer.class);

    private final KafkaProducerHealthIndicator indicator = new KafkaProducerHealthIndicator(producer, TOPIC);

    @Test
    @DisplayName("UP quando o produtor enxerga as partições do tópico")
    void up() {
        when(producer.partitionsFor(TOPIC))
                .thenReturn(List.of(new PartitionInfo(TOPIC, 0, null, null, null)));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("partitions", 1);
    }

    @Test
    @DisplayName("DOWN quando o produtor não obtém os metadados (broker inalcançável)")
    void downSemMetadata() {
        when(producer.partitionsFor(TOPIC)).thenThrow(new TimeoutException("metadata não chegou"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    @DisplayName("DOWN quando o tópico não tem partições (tópico inexistente)")
    void downTopicoVazio() {
        when(producer.partitionsFor(TOPIC)).thenReturn(List.of());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
