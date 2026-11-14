package br.com.motorfraude.receiver.adapters.in.rest;

/** Desfecho de uma requisição ao receiver; vira o label {@code status} da métrica {@code receiver_requests_total}. */
public enum Outcome {
    ACCEPTED,
    DUPLICATE,
    IN_FLIGHT,
    /** Contrato ou invariante inválido (400). */
    REJECTED,
    RATE_LIMITED,
    /** Kafka não confirmou (503). */
    UNAVAILABLE,
    /** Origem ausente ou fora da lista (401). */
    UNAUTHORIZED,
    /** Corpo acima de 16 KB (413). */
    TOO_LARGE
}
