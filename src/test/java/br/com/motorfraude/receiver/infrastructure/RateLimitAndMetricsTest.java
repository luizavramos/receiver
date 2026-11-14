package br.com.motorfraude.receiver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * H4 e T119 com o serviço Spring inteiro (sem Kafka nem Redis: só chegam requisições que são barradas antes): limite
 * por origem, isolamento entre origens e métricas expostas em /actuator/prometheus.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "receiver.rate-limit-per-second=3")
class RateLimitAndMetricsTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> evaluate(String clientId, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transactions:evaluate"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "tx_20261113_0001")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (clientId != null) {
            request.header("X-Client-Id", clientId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String prometheus() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @DisplayName("H4: origem que excede N req/s recebe 429 com Retry-After; outra origem não é afetada; métricas por desfecho")
    void limitePorOrigemEMetricas() throws Exception {
        var statuses = new ArrayList<Integer>();
        HttpResponse<String> lastLimited = null;
        for (int i = 0; i < 12; i++) {
            var response = evaluate("pix-core", "{}");
            statuses.add(response.statusCode());
            if (response.statusCode() == 429) {
                lastLimited = response;
            }
        }
        var otherOrigin = evaluate("ted-core", "{}");
        var intruder = evaluate("intruso-qualquer", "{}");

        // com capacidade 3, as 3 primeiras sempre passam pelo limite (e caem na validação: 400)
        assertThat(statuses.subList(0, 3)).containsOnly(400);
        assertThat(statuses).contains(429);
        assertThat(lastLimited.headers().firstValue("Retry-After")).contains("1");
        assertThat(lastLimited.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(otherOrigin.statusCode()).as("outra origem não é afetada").isEqualTo(400);
        assertThat(intruder.statusCode()).isEqualTo(401);

        var metrics = prometheus();
        List<String> requests = metrics.lines().filter(l -> l.startsWith("receiver_requests_total")).toList();
        assertThat(requests).anyMatch(l -> l.contains("status=\"REJECTED\"") && l.contains("client=\"pix-core\""));
        assertThat(requests).anyMatch(l -> l.contains("status=\"RATE_LIMITED\"") && l.contains("client=\"pix-core\""));
        assertThat(requests).anyMatch(l -> l.contains("status=\"REJECTED\"") && l.contains("client=\"ted-core\""));
        assertThat(requests).anyMatch(l -> l.contains("status=\"UNAUTHORIZED\"") && l.contains("client=\"unknown\""));
        assertThat(metrics).as("o valor do header de origem desconhecida nunca vira label").doesNotContain("intruso-qualquer");
        assertThat(metrics).as("o histograma existe desde a partida, mesmo sem publicações").contains("receiver_publish_seconds_count");
    }
}
