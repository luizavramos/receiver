package br.com.motorfraude.receiver.domain;

import br.com.motorfraude.contracts.EvaluateTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Comando de avaliação já aceito pelas invariantes de domínio (spec 001, FR-002). O JSON Schema barra a forma; aqui
 * ficam as regras que dependem de contexto (relógio) ou que reforçam o contrato.
 *
 * <p>O "agora" entra por parâmetro, nunca por chamada direta ao relógio (constituição VIII).
 */
public record TransactionCommand(EvaluateTransaction payload) {

    /** Tolerância de relógio entre a origem e o receiver. */
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private static final String ACCEPTED_CURRENCY = "BRL";

    public TransactionCommand {
        Objects.requireNonNull(payload, "payload");
    }

    /** @throws InvalidTransactionException com todas as violações encontradas */
    public static TransactionCommand of(EvaluateTransaction payload, Instant now) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(now, "now");

        var violations = new ArrayList<String>();
        if (payload.amount() == null || payload.amount().value() <= 0) {
            violations.add("amount.value: deve ser maior que zero");
        }
        if (payload.amount() == null || !ACCEPTED_CURRENCY.equals(payload.amount().currency())) {
            violations.add("amount.currency: somente " + ACCEPTED_CURRENCY + " é aceito");
        }
        if (payload.occurredAt() == null || payload.occurredAt().isAfter(now.plus(MAX_FUTURE_SKEW))) {
            violations.add("occurredAt: não pode estar mais de 5 minutos no futuro");
        }
        if (!violations.isEmpty()) {
            throw new InvalidTransactionException(violations);
        }
        return new TransactionCommand(payload);
    }

    public String transactionId() {
        return payload.transactionId();
    }

    /** Chave da mensagem Kafka: mantém a ordem por conta. */
    public String accountId() {
        return payload.accountId();
    }
}
