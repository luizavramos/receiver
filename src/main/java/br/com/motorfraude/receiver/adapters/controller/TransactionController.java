package br.com.motorfraude.receiver.adapters.controller;

import br.com.motorfraude.receiver.adapters.controller.TransactionRequestParser.Parsed;
import br.com.motorfraude.receiver.adapters.presenter.Reply;
import br.com.motorfraude.receiver.adapters.presenter.TransactionPresenter;
import br.com.motorfraude.receiver.application.ReceiveTransactionUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP do receiver (spec 001, plan §4). Só coordena a ordem das checagens: origem → limite de taxa → corpo
 * (até 16 KB) → contrato e invariantes → caso de uso. O limite vem antes de ler o corpo: lixo também é limitado. Cada
 * etapa mora numa classe própria; todo desfecho é reportado às métricas.
 */
@RestController
public class TransactionController {

    /** Label usado nas métricas para qualquer origem que não esteja na lista: evita cardinalidade ilimitada. */
    public static final String UNKNOWN_CLIENT = "unknown";

    private final ReceiveTransactionUseCase useCase;
    private final AllowedClients allowedClients;
    private final RequestBodyReader bodyReader;
    private final TransactionRequestParser parser;
    private final TraceContextResolver traceResolver;
    private final TransactionPresenter presenter;
    private final RequestMetrics metrics;

    public TransactionController(ReceiveTransactionUseCase useCase, AllowedClients allowedClients, RequestBodyReader bodyReader,
            TransactionRequestParser parser, TraceContextResolver traceResolver, TransactionPresenter presenter, RequestMetrics metrics) {
        this.useCase = useCase;
        this.allowedClients = allowedClients;
        this.bodyReader = bodyReader;
        this.parser = parser;
        this.traceResolver = traceResolver;
        this.presenter = presenter;
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
            return presenter.unauthorized();
        }
        if (!useCase.admit(clientId)) {
            return presenter.rateLimited();
        }

        var body = bodyReader.read(request);
        if (body.isEmpty()) {
            return presenter.tooLarge(bodyReader.maxBytes());
        }

        return switch (parser.parse(body.get(), idempotencyKey)) {
            case Parsed.Invalid invalid -> presenter.invalid(invalid.errors());
            case Parsed.Valid valid -> presenter.respond(useCase.execute(valid.command(), clientId, traceResolver.resolve(traceparent)));
        };
    }
}
