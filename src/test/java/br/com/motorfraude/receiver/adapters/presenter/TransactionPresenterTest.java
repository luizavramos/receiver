package br.com.motorfraude.receiver.adapters.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.receiver.domain.ReceiveResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class TransactionPresenterTest {

    private final TransactionPresenter presenter = new TransactionPresenter();

    @Test
    @DisplayName("aceita: 202 com transactionId e status ACCEPTED")
    void aceita() {
        var reply = presenter.respond(new ReceiveResult.Accepted("tx_1"));

        assertThat(reply.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(reply.response().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reply.response().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(reply.response().getBody()).containsEntry("transactionId", "tx_1").containsEntry("status", "ACCEPTED");
    }

    @Test
    @DisplayName("duplicata: também 202, com status DUPLICATE")
    void duplicata() {
        var reply = presenter.respond(new ReceiveResult.Duplicate("tx_1"));

        assertThat(reply.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(reply.response().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reply.response().getBody()).containsEntry("status", "DUPLICATE");
    }

    @Test
    @DisplayName("em andamento e indisponível: 503 com Retry-After 1, e desfechos diferentes")
    void tentarDeNovo() {
        var inFlight = presenter.respond(new ReceiveResult.InFlight("tx_1"));
        var unavailable = presenter.respond(new ReceiveResult.Unavailable("tx_1"));

        assertThat(inFlight.outcome()).isEqualTo(Outcome.IN_FLIGHT);
        assertThat(unavailable.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        for (var reply : List.of(inFlight, unavailable)) {
            assertThat(reply.response().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(reply.response().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(reply.response().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    @DisplayName("limite de taxa: 429 com Retry-After, tanto vindo do caso de uso quanto direto")
    void limiteDeTaxa() {
        var viaUseCase = presenter.respond(new ReceiveResult.RateLimited("pix-core"));
        var direct = presenter.rateLimited();

        for (var reply : List.of(viaUseCase, direct)) {
            assertThat(reply.outcome()).isEqualTo(Outcome.RATE_LIMITED);
            assertThat(reply.response().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(reply.response().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("inválida: 400 em Problem Details com a lista de erros; rejeitada pelo caso de uso também")
    void invalida() {
        var direct = presenter.invalid(List.of("amount.value: deve ser positivo"));
        var viaUseCase = presenter.respond(new ReceiveResult.Rejected(List.of("amount.value: deve ser positivo")));

        for (var reply : List.of(direct, viaUseCase)) {
            assertThat(reply.outcome()).isEqualTo(Outcome.REJECTED);
            assertThat(reply.response().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(reply.response().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(reply.response().getBody()).containsEntry("status", 400).containsEntry("type", "about:blank");
            assertThat(reply.response().getBody().get("errors")).isEqualTo(List.of("amount.value: deve ser positivo"));
        }
    }

    @Test
    @DisplayName("sem erros a lista não aparece no corpo")
    void semListaDeErros() {
        assertThat(presenter.unauthorized().response().getBody()).doesNotContainKey("errors");
    }

    @Test
    @DisplayName("origem não autorizada: 401")
    void naoAutorizada() {
        var reply = presenter.unauthorized();

        assertThat(reply.outcome()).isEqualTo(Outcome.UNAUTHORIZED);
        assertThat(reply.response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reply.response().getBody()).containsEntry("title", "Origem não autorizada");
    }

    @Test
    @DisplayName("corpo grande demais: 413 dizendo o limite")
    void grandeDemais() {
        var reply = presenter.tooLarge(16384);

        assertThat(reply.outcome()).isEqualTo(Outcome.TOO_LARGE);
        assertThat(reply.response().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat((String) reply.response().getBody().get("detail")).contains("16384");
    }
}
