package br.com.motorfraude.receiver.adapters.presenter;

import br.com.motorfraude.receiver.domain.ReceiveResult;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Traduz cada desfecho do recebimento em resposta HTTP e no {@link Outcome} da métrica. Não decide nada, só apresenta. */
public class TransactionPresenter {

    public Reply respond(ReceiveResult result) {
        return switch (result) {
            case ReceiveResult.Accepted accepted -> accepted(Outcome.ACCEPTED, accepted.transactionId(), "ACCEPTED");
            case ReceiveResult.Duplicate duplicate -> accepted(Outcome.DUPLICATE, duplicate.transactionId(), "DUPLICATE");
            case ReceiveResult.RateLimited limited -> rateLimited();
            case ReceiveResult.InFlight inFlight -> ProblemDetails.retryLater(Outcome.IN_FLIGHT, HttpStatus.SERVICE_UNAVAILABLE,
                    "Transação em andamento", "A mesma transação ainda está sendo processada; tente novamente");
            case ReceiveResult.Unavailable unavailable -> ProblemDetails.retryLater(Outcome.UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Serviço indisponível", "A transação não foi aceita; tente novamente");
            case ReceiveResult.Rejected rejected -> invalid(rejected.errors());
        };
    }

    public Reply unauthorized() {
        return ProblemDetails.of(Outcome.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Origem não autorizada",
                "X-Client-Id ausente ou desconhecido", List.of());
    }

    public Reply rateLimited() {
        return ProblemDetails.retryLater(Outcome.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "Limite de requisições excedido",
                "A origem excedeu o limite de requisições; tente novamente");
    }

    public Reply tooLarge(int maxBytes) {
        return ProblemDetails.of(Outcome.TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "Corpo grande demais",
                "O corpo deve ter no máximo " + maxBytes + " bytes", List.of());
    }

    public Reply invalid(List<String> errors) {
        return ProblemDetails.of(Outcome.REJECTED, HttpStatus.BAD_REQUEST, "Requisição inválida",
                "O corpo ou os headers não respeitam o contrato", errors);
    }

    private static Reply accepted(Outcome outcome, String transactionId, String status) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("transactionId", transactionId);
        payload.put("status", status);
        return new Reply(outcome, ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(payload));
    }
}
