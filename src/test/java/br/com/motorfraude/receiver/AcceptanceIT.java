package br.com.motorfraude.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.receiver.infrastructure.ReceiverApplication;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * T115 e T116 — aceite de H3 e H5 com o serviço inteiro, Kafka e Redis reais. "Parar" Kafka ou Redis é
 * {@code docker pause}: o container fica de pé, mas não responde (o pior caso: lento, não caído).
 */
@Testcontainers
@SpringBootTest(classes = ReceiverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptanceIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("receiver.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("receiver.redis.host", REDIS::getHost);
        registry.add("receiver.redis.port", () -> REDIS.getMappedPort(6379));
        // Folga para o aquecimento da JVM nos testes; o valor de 5 ms da spec é provado em RedisDuplicateGuardIT
        registry.add("receiver.redis.command-timeout", () -> "300ms");
    }

    private static RedisClient inspectorClient;

    @BeforeAll
    static void setUp() throws Exception {
        KafkaTestSupport.createTopic(KAFKA.getBootstrapServers());
        inspectorClient = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
    }

    @AfterAll
    static void tearDown() {
        inspectorClient.shutdown();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private static String newId() {
        return "tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String body(String transactionId) {
        return """
                {"schemaVersion":1,"transactionId":"%s","accountId":"acc_tok_00000001","customerId":"cus_tok_00000001",
                 "type":"PIX","amount":{"value":15000,"currency":"BRL"},
                 "counterparty":{"accountToken":"acc_tok_00004711","bankIspb":"60701190"},
                 "channel":"MOBILE","deviceIdHash":"dev_0f3a9c1d5b7e2a64","occurredAt":"%s"}
                """.formatted(transactionId, Instant.now().minusSeconds(5));
    }

    private HttpResponse<String> post(String transactionId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transactions:evaluate"))
                .header("Content-Type", "application/json")
                .header("X-Client-Id", "pix-core")
                .header("Idempotency-Key", transactionId)
                .POST(HttpRequest.BodyPublishers.ofString(body(transactionId)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long publishedCount(String transactionId) {
        return KafkaTestSupport.readAll(KAFKA.getBootstrapServers(), Duration.ofSeconds(2)).stream()
                .filter(r -> new String(r.value()).contains(transactionId))
                .count();
    }

    private static void pause(GenericContainer<?> container) {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    private static void unpause(GenericContainer<?> container) {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }

    @Test
    @DisplayName("H3.1: reenvio depois do ack → 202 DUPLICATE, e só uma mensagem no tópico (SC-003)")
    void reenvioViraDuplicate() throws Exception {
        var id = newId();

        var first = post(id);
        var second = post(id);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.body()).contains("\"status\":\"ACCEPTED\"");
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.body()).contains("\"status\":\"DUPLICATE\"");
        assertThat(publishedCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("H3.2: Redis fora do ar → fail-open: as duas são aceitas e publicadas (o motor resolve a duplicata)")
    void redisForaDoArFailOpen() throws Exception {
        var id = newId();

        pause(REDIS);
        HttpResponse<String> first;
        HttpResponse<String> second;
        try {
            first = post(id);
            second = post(id);
        } finally {
            unpause(REDIS);
        }

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.body()).contains("\"status\":\"ACCEPTED\"");
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.body()).contains("\"status\":\"ACCEPTED\"");
        assertThat(publishedCount(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("H5: Kafka fora do ar → 503 com Retry-After e a chave sai do Redis; com o Kafka de volta, o retry é aceito")
    void kafkaForaDoArEDepoisRetry() throws Exception {
        var id = newId();

        HttpResponse<String> whileDown;
        pause(KAFKA);
        try {
            whileDown = post(id);
            try (var redis = inspectorClient.connect()) {
                assertThat(redis.sync().get("tx:" + id)).as("a reserva foi apagada (release)").isNull();
            }
        } finally {
            unpause(KAFKA);
        }
        var afterRecovery = retryUntilNot503(id);

        assertThat(whileDown.statusCode()).isEqualTo(503);
        assertThat(whileDown.headers().firstValue("Retry-After")).contains("1");
        assertThat(afterRecovery.statusCode()).isEqualTo(202);
        assertThat(afterRecovery.body()).contains("\"status\":\"ACCEPTED\"");
        // 503 significa "não confirmado", não "não gravado": com o broker pausado os bytes já estavam no socket e ele
        // grava ao voltar. Então a mensagem da tentativa que deu 503 pode aparecer, além da do retry (plan 001 §8).
        // Nunca pode faltar a do retry aceito (SC-002) e o motor resolve a duplicata (ADR-10).
        assertThat(publishedCount(id)).isBetween(1L, 2L);
    }

    /** O Kafka precisa de alguns segundos para o produtor voltar a enxergar o broker; o cliente real também reenvia. */
    private HttpResponse<String> retryUntilNot503(String id) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        HttpResponse<String> response = post(id);
        while (response.statusCode() == 503 && System.nanoTime() < deadline) {
            Thread.sleep(500);
            response = post(id);
        }
        return response;
    }

    @Test
    @DisplayName("H3.3: reenvio enquanto a primeira ainda publica → 503 'em andamento', nunca DUPLICATE; depois, aceito")
    void reenvioDuranteAPublicacao() throws Exception {
        var id = newId();

        HttpResponse<String> inFlight;
        Future<HttpResponse<String>> first;
        pause(KAFKA);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            first = pool.submit(() -> post(id));
            Thread.sleep(500); // a primeira já reservou (PENDING) e está esperando o ack
            inFlight = post(id);
            first.get();
        } finally {
            unpause(KAFKA);
        }

        assertThat(inFlight.statusCode()).isEqualTo(503);
        assertThat(inFlight.body()).contains("Transação em andamento").doesNotContain("DUPLICATE");
        assertThat(retryUntilNot503(id).statusCode()).isEqualTo(202);
    }

    @Test
    @DisplayName("T116: 50 POSTs simultâneos do mesmo transactionId → 1 publicação e um único ACCEPTED")
    void concorrencia() throws Exception {
        var id = newId();
        var start = new CountDownLatch(1);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return post(id);
                }));
            }
            start.countDown();
            for (var f : futures) {
                responses.add(f.get());
            }
        }

        assertThat(publishedCount(id)).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.body().contains("\"status\":\"ACCEPTED\"")))
                .as("só uma ganha a reserva")
                .hasSize(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 202 && r.body().contains("DUPLICATE")
                        || r.statusCode() == 503 && r.body().contains("Transação em andamento")))
                .as("as demais: DUPLICATE (já confirmada) ou 503 em andamento")
                .hasSize(49);
    }
}
