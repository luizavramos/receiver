package br.com.motorfraude.receiver.adapters.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.motorfraude.commons.tracing.TraceParent;
import br.com.motorfraude.contracts.Channel;
import br.com.motorfraude.contracts.ContractAssertions;
import br.com.motorfraude.contracts.Counterparty;
import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.JsonCodec;
import br.com.motorfraude.contracts.Money;
import br.com.motorfraude.contracts.TransactionType;
import br.com.motorfraude.receiver.KafkaTestSupport;
import br.com.motorfraude.receiver.application.port.out.PublishFailed;
import br.com.motorfraude.receiver.application.port.out.TraceContext;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import br.com.motorfraude.receiver.infrastructure.config.KafkaConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** T110 — o comando chega ao tópico com chave e headers corretos, e o valor respeita o contrato (000 plan §3.3). */
@Testcontainers
class KafkaCommandPublisherIT {

    private static final Instant NOW = Instant.parse("2026-11-13T21:30:00Z");
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    private static KafkaProducer<String, byte[]> producer;
    private static KafkaCommandPublisher publisher;

    @BeforeAll
    static void setUp() throws Exception {
        KafkaTestSupport.createTopic(KAFKA.getBootstrapServers());
        producer = new KafkaProducer<>(KafkaConfig.producerProperties(KAFKA.getBootstrapServers()));
        publisher = new KafkaCommandPublisher(
                producer, KafkaTestSupport.TOPIC, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(3));
    }

    @AfterAll
    static void tearDown() {
        producer.close(Duration.ofSeconds(2));
    }

    private static TransactionCommand command(String transactionId, String accountId) {
        return TransactionCommand.of(
                new EvaluateTransaction(
                        1,
                        transactionId,
                        accountId,
                        "cus_tok_00000001",
                        TransactionType.PIX,
                        new Money(15_000, "BRL"),
                        new Counterparty("acc_tok_00004711", "60701190"),
                        Channel.MOBILE,
                        "dev_0f3a9c1d5b7e2a64",
                        NOW.minusSeconds(30)),
                NOW);
    }

    @Test
    @DisplayName("H1: chave = accountId, headers padrão e valor aderente ao contrato evaluate-transaction.v1")
    void publicaComChaveEHeaders() throws Exception {
        publisher.publish(command("tx_it_publica_0001", "acc_tok_00000001"), new TraceContext(TRACEPARENT));

        var records = KafkaTestSupport.readAll(KAFKA.getBootstrapServers(), Duration.ofSeconds(2));
        var record = records.stream()
                .filter(r -> new String(r.value()).contains("tx_it_publica_0001"))
                .findFirst()
                .orElseThrow();

        assertThat(record.key()).isEqualTo("acc_tok_00000001");
        assertThat(KafkaTestSupport.header(record, "traceparent")).isEqualTo(TRACEPARENT);
        assertThat(KafkaTestSupport.header(record, "receivedAt")).isEqualTo(NOW.toString());
        assertThat(KafkaTestSupport.header(record, "schemaVersion")).isEqualTo("1");
        assertThat(KafkaTestSupport.header(record, "commandType")).isEqualTo("EvaluateTransaction");
        ContractAssertions.assertProduces("evaluate-transaction.v1", record.value());
        assertThat(JsonCodec.fromJson(record.value(), EvaluateTransaction.class).transactionId())
                .isEqualTo("tx_it_publica_0001");
    }

    @Test
    @DisplayName("sem traceparent na requisição, o publicador começa um trace novo e válido")
    void semTraceCriaTraceNovo() throws Exception {
        publisher.publish(command("tx_it_semtrace_0002", "acc_tok_00000002"), TraceContext.none());

        var record = KafkaTestSupport.readAll(KAFKA.getBootstrapServers(), Duration.ofSeconds(2)).stream()
                .filter(r -> new String(r.value()).contains("tx_it_semtrace_0002"))
                .findFirst()
                .orElseThrow();

        assertThat(TraceParent.parse(KafkaTestSupport.header(record, "traceparent"))).isPresent();
    }

    @Test
    @DisplayName("mesma conta, mesma partição: a chave mantém a ordem por conta")
    void mesmaContaMesmaParticao() throws Exception {
        publisher.publish(command("tx_it_ordem_0003a", "acc_tok_00000003"), TraceContext.none());
        publisher.publish(command("tx_it_ordem_0003b", "acc_tok_00000003"), TraceContext.none());

        var partitions = KafkaTestSupport.readAll(KAFKA.getBootstrapServers(), Duration.ofSeconds(2)).stream()
                .filter(r -> "acc_tok_00000003".equals(r.key()))
                .map(r -> r.partition())
                .distinct()
                .toList();

        assertThat(partitions).hasSize(1);
    }

    @Test
    @DisplayName("H5: Kafka inalcançável → PublishFailed dentro do prazo, sem travar a requisição")
    void kafkaInalcancavel() {
        try (var deadProducer = new KafkaProducer<String, byte[]>(KafkaConfig.producerProperties("localhost:1"))) {
            var failing = new KafkaCommandPublisher(
                    deadProducer, KafkaTestSupport.TOPIC, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(3));
            var started = System.nanoTime();

            assertThatThrownBy(() -> failing.publish(command("tx_it_falha_0004", "acc_tok_00000004"), TraceContext.none()))
                    .isInstanceOf(PublishFailed.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
