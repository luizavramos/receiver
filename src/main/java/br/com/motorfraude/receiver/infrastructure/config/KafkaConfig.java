package br.com.motorfraude.receiver.infrastructure.config;

import br.com.motorfraude.receiver.adapters.out.kafka.KafkaCommandPublisher;
import br.com.motorfraude.receiver.application.port.out.CommandPublisher;
import br.com.motorfraude.receiver.infrastructure.observability.KafkaProducerHealthIndicator;
import br.com.motorfraude.receiver.infrastructure.observability.TimedCommandPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Produtor Kafka nativo e o publicador de comandos (plan 001 §2). */
@Configuration
public class KafkaConfig {

    /**
     * Garantias do plan §2: idempotente, {@code acks=all}, lote curto e compressão. O prazo de entrega (2 s) é maior que
     * {@code linger.ms + request.timeout.ms}, como o Kafka exige. {@code max.block.ms} limita a espera por metadata
     * quando o broker está fora: a requisição nunca fica presa por mais que isso.
     */
    public static Map<String, Object> producerProperties(String bootstrapServers) {
        var props = new HashMap<String, Object>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "receiver");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        return props;
    }

    @Bean(destroyMethod = "close")
    Producer<String, byte[]> commandProducer(ReceiverProperties properties) {
        return new KafkaProducer<>(producerProperties(properties.kafka().bootstrapServers()));
    }

    @Bean
    CommandPublisher commandPublisher(
            Producer<String, byte[]> producer, ReceiverProperties properties, Clock clock, MeterRegistry registry) {
        var kafka = properties.kafka();
        var publisher = new KafkaCommandPublisher(producer, kafka.topic(), clock, kafka.publishTimeout());
        return new TimedCommandPublisher(publisher, registry);
    }

    /** Entra no grupo de readiness (application.yml) com o nome {@code kafkaProducer}. */
    @Bean
    KafkaProducerHealthIndicator kafkaProducerHealthIndicator(Producer<String, byte[]> producer, ReceiverProperties properties) {
        return new KafkaProducerHealthIndicator(producer, properties.kafka().topic());
    }
}
