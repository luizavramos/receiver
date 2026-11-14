package br.com.motorfraude.receiver.domain;

import java.util.List;

/** Desfecho do recebimento de uma transação (spec 001, entidades). O adapter REST traduz cada um em status HTTP. */
public sealed interface ReceiveResult {

    /** Publicado e confirmado pelo Kafka: 202 ACCEPTED. */
    record Accepted(String transactionId) implements ReceiveResult {}

    /** Já aceito antes (chave DONE no Redis): 202 DUPLICATE, nada publicado. */
    record Duplicate(String transactionId) implements ReceiveResult {}

    /** A mesma transação ainda está sendo publicada (chave PENDING): 503 com Retry-After. */
    record InFlight(String transactionId) implements ReceiveResult {}

    /** Contrato ou invariante inválido: 400. Produzido pelo adapter de entrada, antes do caso de uso. */
    record Rejected(List<String> errors) implements ReceiveResult {
        public Rejected {
            errors = List.copyOf(errors);
        }
    }

    /** Origem acima do limite: 429 com Retry-After. */
    record RateLimited(String clientId) implements ReceiveResult {}

    /** Kafka não confirmou a gravação: 503 com Retry-After. */
    record Unavailable(String transactionId) implements ReceiveResult {}
}
