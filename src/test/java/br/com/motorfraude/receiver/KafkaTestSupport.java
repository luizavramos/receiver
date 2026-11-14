package br.com.motorfraude.receiver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Apoio dos testes de integração: criar o tópico e ler o que foi publicado. */
public final class KafkaTestSupport {

    public static final String TOPIC = "transaction-commands.v1";

    private KafkaTestSupport() {}

    public static void createTopic(String bootstrapServers) throws Exception {
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
    }

    /** Lê o tópico desde o início até ficar {@code quiet} sem mensagens novas. */
    public static List<ConsumerRecord<String, byte[]>> readAll(String bootstrapServers, Duration quiet) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var records = new ArrayList<ConsumerRecord<String, byte[]>>();
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            consumer.subscribe(List.of(TOPIC));
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            var lastSeen = System.nanoTime();
            while (System.nanoTime() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(records::add);
                if (!polled.isEmpty()) {
                    lastSeen = System.nanoTime();
                } else if (!records.isEmpty() && System.nanoTime() - lastSeen > quiet.toNanos()) {
                    break;
                }
            }
        }
        return records;
    }

    public static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
