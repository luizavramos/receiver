package br.com.motorfraude.receiver.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.motorfraude.receiver.application.ReceiveTransactionUseCase;
import br.com.motorfraude.receiver.application.port.out.DuplicateGuard.Reservation;
import br.com.motorfraude.receiver.fakes.FakeCommandPublisher;
import br.com.motorfraude.receiver.fakes.FakeDuplicateGuard;
import br.com.motorfraude.receiver.fakes.FakeRateLimiter;
import br.com.motorfraude.receiver.fakes.FakeRequestMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** T108 — status e Problem Details (RFC 9457) de cada cenário de H1 e H2, sem subir Spring. */
class TransactionControllerTest {

    private static final String PATH = "/v1/transactions:evaluate";
    private static final Instant NOW = Instant.parse("2026-11-13T21:30:00Z");
    private static final String TX_ID = "tx_20261113_0001";

    private final FakeRateLimiter rateLimiter = new FakeRateLimiter();
    private final FakeDuplicateGuard guard = new FakeDuplicateGuard();
    private final FakeCommandPublisher publisher = new FakeCommandPublisher();
    private final FakeRequestMetrics metrics = new FakeRequestMetrics();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var useCase = new ReceiveTransactionUseCase(rateLimiter, guard, publisher);
        var controller = new TransactionController(
                useCase,
                new RequestValidator(),
                new AllowedClients(Set.of("pix-core", "ted-core")),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TraceContextResolver(() -> null),
                metrics);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String validBody() {
        return """
                {
                  "schemaVersion": 1,
                  "transactionId": "tx_20261113_0001",
                  "accountId": "acc_tok_00000001",
                  "customerId": "cus_tok_00000001",
                  "type": "PIX",
                  "amount": { "value": 15000, "currency": "BRL" },
                  "counterparty": { "accountToken": "acc_tok_00004711", "bankIspb": "60701190" },
                  "channel": "MOBILE",
                  "deviceIdHash": "dev_0f3a9c1d5b7e2a64",
                  "occurredAt": "2026-11-13T21:29:30Z"
                }
                """;
    }

    private static MockHttpServletRequestBuilder request(String body) {
        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "pix-core")
                .header("Idempotency-Key", TX_ID)
                .content(body.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(request(body));
    }

    private void assertProblem(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty());
    }

    // ---------- H1 ----------

    @Test
    @DisplayName("H1: transação válida → 202 ACCEPTED e o comando é publicado")
    void aceita() throws Exception {
        send(validBody())
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionId").value(TX_ID))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.get(0).accountId()).isEqualTo("acc_tok_00000001");
    }

    @Test
    @DisplayName("H3: reenvio de transação já aceita → 202 DUPLICATE, nada publicado")
    void duplicata() throws Exception {
        guard.nextReservation = Reservation.ALREADY_SEEN;

        send(validBody())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").value(TX_ID))
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("traceparent recebido segue para o publicador (sem tracer, o header da requisição vale)")
    void tracePropagado() throws Exception {
        var traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        mvc.perform(request(validBody()).header("traceparent", traceparent)).andExpect(status().isAccepted());

        assertThat(publisher.traces.get(0).traceparent()).isEqualTo(traceparent);
    }

    // ---------- H2 ----------

    @Test
    @DisplayName("H2.1: corpo sem transactionId → 400 Problem Details apontando o campo, nada publicado")
    void semTransactionId() throws Exception {
        var body = validBody().replace("\"transactionId\": \"tx_20261113_0001\",", "");

        var result = send(body);

        assertProblem(result, 400);
        result.andExpect(jsonPath("$.errors[*]").value(org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString("transactionId"))));
        assertThat(publisher.published).isEmpty();
        assertThat(guard.reserved).isEmpty();
    }

    @Test
    @DisplayName("H2.2: amount.value zero → 400")
    void valorZero() throws Exception {
        var result = send(validBody().replace("15000", "0"));

        assertProblem(result, 400);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("H2.2: amount.value decimal → 400")
    void valorDecimal() throws Exception {
        var result = send(validBody().replace("15000", "150.5"));

        assertProblem(result, 400);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("H2.2: campo desconhecido (ex.: cpf) → 400: o schema é estrito")
    void campoExtra() throws Exception {
        var body = validBody().replace("\"channel\": \"MOBILE\",", "\"channel\": \"MOBILE\", \"cpf\": \"12345678909\",");

        assertProblem(send(body), 400);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("H2.3: Idempotency-Key diferente do transactionId → 400")
    void idempotencyKeyDivergente() throws Exception {
        var result = mvc.perform(request(validBody()).header("Idempotency-Key", "tx_outra_chave_999"));

        assertProblem(result, 400);
        result.andExpect(jsonPath("$.errors[*]").value(org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString("Idempotency-Key"))));
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("sem o header Idempotency-Key → 400")
    void semIdempotencyKey() throws Exception {
        var builder = post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "pix-core")
                .content(validBody().getBytes(StandardCharsets.UTF_8));

        assertProblem(mvc.perform(builder), 400);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("invariante de domínio: occurredAt mais de 5 min no futuro → 400")
    void futuroDistante() throws Exception {
        var body = validBody().replace("2026-11-13T21:29:30Z", "2026-11-13T22:30:00Z");

        var result = send(body);

        assertProblem(result, 400);
        result.andExpect(jsonPath("$.errors[*]").value(org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString("occurredAt"))));
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("JSON malformado → 400")
    void jsonMalformado() throws Exception {
        assertProblem(send("{ isto nao e json"), 400);
        assertThat(publisher.published).isEmpty();
    }

    // ---------- limites e origem ----------

    @Test
    @DisplayName("corpo acima de 16 KB → 413 Problem Details, nada publicado")
    void corpoGrande() throws Exception {
        var body = "{\"x\":\"" + "a".repeat(16 * 1024) + "\"}";

        assertProblem(send(body), 413);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("corpo de exatamente 16 KB não é barrado pelo limite de tamanho")
    void corpoNoLimite() throws Exception {
        var padding = " ".repeat(16 * 1024 - validBody().getBytes(StandardCharsets.UTF_8).length);

        send(validBody() + padding).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("X-Client-Id ausente → 401, nada lido nem publicado")
    void semClientId() throws Exception {
        var builder = post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", TX_ID)
                .content(validBody().getBytes(StandardCharsets.UTF_8));

        assertProblem(mvc.perform(builder), 401);
        assertThat(publisher.published).isEmpty();
        assertThat(rateLimiter.asked).isEmpty();
    }

    @Test
    @DisplayName("X-Client-Id fora da lista de origens → 401")
    void clientIdDesconhecido() throws Exception {
        assertProblem(mvc.perform(request(validBody()).header("X-Client-Id", "intruso")), 401);
        assertThat(publisher.published).isEmpty();
        assertThat(rateLimiter.asked).isEmpty();
    }

    // ---------- H4 / H5 ----------

    @Test
    @DisplayName("H4: origem acima do limite → 429 com Retry-After")
    void rateLimited() throws Exception {
        rateLimiter.allow = false;

        assertProblem(send(validBody()), 429);
        send(validBody()).andExpect(header().string("Retry-After", "1"));
        assertThat(publisher.published).isEmpty();
        assertThat(guard.reserved).isEmpty();
    }

    @Test
    @DisplayName("T117: origem acima do limite com corpo inválido → 429, não 400 (o limite vem antes de validar)")
    void rateLimitAntesDaValidacao() throws Exception {
        rateLimiter.allow = false;

        assertProblem(send("{ isto nao e json"), 429);
        assertProblem(send(validBody().replace("15000", "0")), 429);
    }

    @Test
    @DisplayName("cada requisição consulta o limitador exatamente uma vez (sem contar em dobro)")
    void umaConsultaPorRequisicao() throws Exception {
        send(validBody()).andExpect(status().isAccepted());

        assertThat(rateLimiter.asked).containsExactly("pix-core");
    }

    @Test
    @DisplayName("T119: o controller reporta o desfecho de cada requisição; origem desconhecida vira o label 'unknown'")
    void metricasPorDesfecho() throws Exception {
        send(validBody());                                                       // ACCEPTED
        guard.nextReservation = Reservation.ALREADY_SEEN;
        send(validBody());                                                       // DUPLICATE
        guard.nextReservation = Reservation.IN_FLIGHT;
        send(validBody());                                                       // IN_FLIGHT
        guard.nextReservation = Reservation.RESERVED;
        publisher.failing = true;
        send(validBody());                                                       // UNAVAILABLE
        publisher.failing = false;
        send("{ isto nao e json");                                               // REJECTED
        send("{\"x\":\"" + "a".repeat(16 * 1024) + "\"}");                       // TOO_LARGE
        mvc.perform(request(validBody()).header("X-Client-Id", "intruso"));      // UNAUTHORIZED
        rateLimiter.allow = false;
        send(validBody());                                                       // RATE_LIMITED

        assertThat(metrics.recorded).containsExactly(
                new FakeRequestMetrics.Recorded(Outcome.ACCEPTED, "pix-core"),
                new FakeRequestMetrics.Recorded(Outcome.DUPLICATE, "pix-core"),
                new FakeRequestMetrics.Recorded(Outcome.IN_FLIGHT, "pix-core"),
                new FakeRequestMetrics.Recorded(Outcome.UNAVAILABLE, "pix-core"),
                new FakeRequestMetrics.Recorded(Outcome.REJECTED, "pix-core"),
                new FakeRequestMetrics.Recorded(Outcome.TOO_LARGE, "pix-core"),
                new FakeRequestMetrics.Recorded(Outcome.UNAUTHORIZED, "unknown"),
                new FakeRequestMetrics.Recorded(Outcome.RATE_LIMITED, "pix-core"));
    }

    @Test
    @DisplayName("H5: Kafka indisponível → 503 com Retry-After")
    void kafkaIndisponivel() throws Exception {
        publisher.failing = true;

        var result = send(validBody());

        assertProblem(result, 503);
        result.andExpect(header().string("Retry-After", "1"));
        assertThat(guard.released).containsExactly(TX_ID);
    }

    @Test
    @DisplayName("mesma transação ainda sendo publicada → 503 com Retry-After (nunca DUPLICATE)")
    void emAndamento() throws Exception {
        guard.nextReservation = Reservation.IN_FLIGHT;

        var result = send(validBody());

        assertProblem(result, 503);
        result.andExpect(header().string("Retry-After", "1"));
        assertThat(publisher.published).isEmpty();
    }
}
