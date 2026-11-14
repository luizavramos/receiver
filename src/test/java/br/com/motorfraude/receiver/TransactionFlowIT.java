package br.com.motorfraude.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.commons.tracing.TraceParent;
import br.com.motorfraude.contracts.ContractAssertions;
import br.com.motorfraude.receiver.infrastructure.ReceiverApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Checkpoint da Fase 3: o serviço inteiro (HTTP → validação → caso de uso → Kafka real) aceita e publica. Cobre H1 e H2
 * de ponta a ponta e a propagação do traceparent da requisição para o header Kafka (T112).
 */
@Testcontainers
@SpringBootTest(classes = ReceiverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionFlowIT {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("receiver.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void createTopic() throws Exception {
        KafkaTestSupport.createTopic(KAFKA.getBootstrapServers());
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private static String body(String transactionId, long amount) {
        return """
                {"schemaVersion":1,"transactionId":"%s","accountId":"acc_tok_00000001","customerId":"cus_tok_00000001",
                 "type":"PIX","amount":{"value":%d,"currency":"BRL"},
                 "counterparty":{"accountToken":"acc_tok_00004711","bankIspb":"60701190"},
                 "channel":"MOBILE","deviceIdHash":"dev_0f3a9c1d5b7e2a64","occurredAt":"%s"}
                """.formatted(transactionId, amount, Instant.now().minusSeconds(5));
    }

    private HttpResponse<String> post(String body, String idempotencyKey, String... extraHeaders) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transactions:evaluate"))
                .header("Content-Type", "application/json")
                .header("X-Client-Id", "pix-core")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < extraHeaders.length; i += 2) {
            request.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("T120: com Kafka e sem Redis o serviço está pronto (200): o Redis não entra na readiness, o receiver é fail-open")
    void prontoSemRedis() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness")).GET().build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("kafkaProducer").doesNotContain("redis");
    }

    @Test
    @DisplayName("H1+H2+T112: válida vira 202 e chega ao tópico com chave, headers e trace; inválida não chega")
    void aceitaEPublicaEBarraLixo() throws Exception {
        var invalid = post(body("tx_flow_invalida_01", 0), "tx_flow_invalida_01");
        var valid = post(
                body("tx_flow_valida_0001", 15_000),
                "tx_flow_valida_0001",
                "traceparent",
                "00-" + TRACE_ID + "-00f067aa0ba902b7-01");

        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(valid.statusCode()).isEqualTo(202);
        assertThat(valid.body()).contains("\"transactionId\":\"tx_flow_valida_0001\"").contains("\"status\":\"ACCEPTED\"");

        var records = KafkaTestSupport.readAll(KAFKA.getBootstrapServers(), Duration.ofSeconds(2));

        assertThat(records).hasSize(1);
        var record = records.get(0);
        assertThat(record.key()).isEqualTo("acc_tok_00000001");
        assertThat(new String(record.value())).doesNotContain("tx_flow_invalida_01");
        ContractAssertions.assertProduces("evaluate-transaction.v1", record.value());
        assertThat(KafkaTestSupport.header(record, "commandType")).isEqualTo("EvaluateTransaction");
        assertThat(KafkaTestSupport.header(record, "schemaVersion")).isEqualTo("1");
        assertThat(Instant.parse(KafkaTestSupport.header(record, "receivedAt")))
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));
        var traceparent = TraceParent.parse(KafkaTestSupport.header(record, "traceparent")).orElseThrow();
        assertThat(traceparent.traceId()).isEqualTo(TRACE_ID);
    }
}
