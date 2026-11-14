package br.com.motorfraude.receiver.adapters.in.rest;

import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.JsonCodec;
import br.com.motorfraude.receiver.application.ReceiveTransactionUseCase;
import br.com.motorfraude.receiver.domain.InvalidTransactionException;
import br.com.motorfraude.receiver.domain.ReceiveResult;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Entrada HTTP do receiver (spec 001, plan §4). Ordem: origem → limite de taxa → corpo (até 16 KB) → contrato →
 * invariantes de domínio → caso de uso. O limite vem antes de ler o corpo: lixo também é limitado. Erros seguem o
 * formato Problem Details (RFC 9457). Todo desfecho é reportado às métricas.
 */
@RestController
public class TransactionController {

    /** Label usado nas métricas para qualquer origem que não esteja na lista: evita cardinalidade ilimitada. */
    public static final String UNKNOWN_CLIENT = "unknown";

    static final int MAX_BODY_BYTES = 16 * 1024;

    /** Resposta HTTP junto com o desfecho que a originou. */
    private record Reply(Outcome outcome, ResponseEntity<Map<String, Object>> response) {}

    private final ReceiveTransactionUseCase useCase;
    private final RequestValidator validator;
    private final AllowedClients allowedClients;
    private final Clock clock;
    private final TraceContextResolver traceResolver;
    private final RequestMetrics metrics;

    public TransactionController(
            ReceiveTransactionUseCase useCase,
            RequestValidator validator,
            AllowedClients allowedClients,
            Clock clock,
            TraceContextResolver traceResolver,
            RequestMetrics metrics) {
        this.useCase = useCase;
        this.validator = validator;
        this.allowedClients = allowedClients;
        this.clock = clock;
        this.traceResolver = traceResolver;
        this.metrics = metrics;
    }

    @PostMapping(path = "/v1/transactions:evaluate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> evaluate(
            HttpServletRequest request,
            @RequestHeader(name = "X-Client-Id", required = false) String clientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "traceparent", required = false) String traceparent)
            throws IOException {

        var reply = process(request, clientId, idempotencyKey, traceparent);
        metrics.record(reply.outcome(), allowedClients.contains(clientId) ? clientId : UNKNOWN_CLIENT);
        return reply.response();
    }

    private Reply process(HttpServletRequest request, String clientId, String idempotencyKey, String traceparent)
            throws IOException {

        if (!allowedClients.contains(clientId)) {
            return problem(Outcome.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Origem não autorizada",
                    "X-Client-Id ausente ou desconhecido", List.of());
        }
        if (!useCase.admit(clientId)) {
            return retryLater(Outcome.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "Limite de requisições excedido",
                    "A origem excedeu o limite de requisições; tente novamente");
        }

        var body = readBody(request);
        if (body == null) {
            return problem(Outcome.TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "Corpo grande demais",
                    "O corpo deve ter no máximo " + MAX_BODY_BYTES + " bytes", List.of());
        }

        var errors = new ArrayList<String>();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            errors.add("Idempotency-Key: header obrigatório");
        }

        JsonNode tree;
        try {
            tree = JsonCodec.readTree(body);
        } catch (JacksonException e) {
            errors.add("corpo: não é um JSON válido");
            return invalid(errors);
        }

        errors.addAll(validator.validate(tree));
        var bodyTransactionId = tree.path("transactionId");
        if (idempotencyKey != null && bodyTransactionId.isString() && !idempotencyKey.equals(bodyTransactionId.asString())) {
            errors.add("Idempotency-Key: deve ser igual ao transactionId do corpo");
        }
        if (!errors.isEmpty()) {
            return invalid(errors);
        }

        TransactionCommand command;
        try {
            command = TransactionCommand.of(JsonCodec.fromTree(tree, EvaluateTransaction.class), clock.instant());
        } catch (InvalidTransactionException e) {
            return invalid(e.violations());
        } catch (JacksonException e) {
            return invalid(List.of("corpo: não corresponde ao contrato"));
        }

        return respond(useCase.execute(command, clientId, traceResolver.resolve(traceparent)));
    }

    /** Lê no máximo o limite + 1 byte: nunca carrega um corpo gigante na memória. Nulo se excedeu. */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        var bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        return bytes.length > MAX_BODY_BYTES ? null : bytes;
    }

    private Reply respond(ReceiveResult result) {
        return switch (result) {
            case ReceiveResult.Accepted accepted -> accepted(Outcome.ACCEPTED, accepted.transactionId(), "ACCEPTED");
            case ReceiveResult.Duplicate duplicate -> accepted(Outcome.DUPLICATE, duplicate.transactionId(), "DUPLICATE");
            case ReceiveResult.RateLimited limited ->
                retryLater(Outcome.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "Limite de requisições excedido",
                        "A origem excedeu o limite de requisições; tente novamente");
            case ReceiveResult.InFlight inFlight ->
                retryLater(Outcome.IN_FLIGHT, HttpStatus.SERVICE_UNAVAILABLE, "Transação em andamento",
                        "A mesma transação ainda está sendo processada; tente novamente");
            case ReceiveResult.Unavailable unavailable ->
                retryLater(Outcome.UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Serviço indisponível",
                        "A transação não foi aceita; tente novamente");
            case ReceiveResult.Rejected rejected -> invalid(rejected.errors());
        };
    }

    private static Reply accepted(Outcome outcome, String transactionId, String status) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("transactionId", transactionId);
        payload.put("status", status);
        return new Reply(outcome,
                ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(payload));
    }

    private static Reply invalid(List<String> errors) {
        return problem(Outcome.REJECTED, HttpStatus.BAD_REQUEST, "Requisição inválida",
                "O corpo ou os headers não respeitam o contrato", errors);
    }

    private static Reply retryLater(Outcome outcome, HttpStatus status, String title, String detail) {
        var response = problem(outcome, status, title, detail, List.of()).response();
        var headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.set(HttpHeaders.RETRY_AFTER, "1");
        return new Reply(outcome, new ResponseEntity<>(response.getBody(), headers, status));
    }

    private static Reply problem(Outcome outcome, HttpStatus status, String title, String detail, List<String> errors) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        if (!errors.isEmpty()) {
            body.put("errors", errors);
        }
        return new Reply(outcome,
                ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body));
    }
}
