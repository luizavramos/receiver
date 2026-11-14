package br.com.motorfraude.receiver.adapters.controller;

import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.JsonCodec;
import br.com.motorfraude.receiver.domain.InvalidTransactionException;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Do corpo cru ao comando de domínio. Confere o JSON, o contrato (JSON Schema), o {@code Idempotency-Key} e as
 * invariantes de domínio, devolvendo todos os erros juntos. A forma da mensagem é assunto desta camada; o caso de uso
 * só recebe um comando já válido.
 */
public class TransactionRequestParser {

    /** Resultado da análise: um comando válido ou a lista de erros. */
    public sealed interface Parsed {
        record Valid(TransactionCommand command) implements Parsed {}

        record Invalid(List<String> errors) implements Parsed {}
    }

    private final RequestValidator validator;
    private final Clock clock;

    public TransactionRequestParser(RequestValidator validator, Clock clock) {
        this.validator = validator;
        this.clock = clock;
    }

    public Parsed parse(byte[] body, String idempotencyKey) {
        var errors = new ArrayList<String>();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            errors.add("Idempotency-Key: header obrigatório");
        }

        JsonNode tree;
        try {
            tree = JsonCodec.readTree(body);
        } catch (JacksonException e) {
            errors.add("corpo: não é um JSON válido");
            return new Parsed.Invalid(errors);
        }

        errors.addAll(validator.validate(tree));
        var bodyTransactionId = tree.path("transactionId");
        if (idempotencyKey != null && bodyTransactionId.isString() && !idempotencyKey.equals(bodyTransactionId.asString())) {
            errors.add("Idempotency-Key: deve ser igual ao transactionId do corpo");
        }
        if (!errors.isEmpty()) {
            return new Parsed.Invalid(errors);
        }

        try {
            return new Parsed.Valid(TransactionCommand.of(JsonCodec.fromTree(tree, EvaluateTransaction.class), clock.instant()));
        } catch (InvalidTransactionException e) {
            return new Parsed.Invalid(e.violations());
        } catch (JacksonException e) {
            return new Parsed.Invalid(List.of("corpo: não corresponde ao contrato"));
        }
    }
}
