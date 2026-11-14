package br.com.motorfraude.receiver.domain;

import java.util.List;

public sealed interface ReceiveResult {

    record Accepted(String transactionId) implements ReceiveResult {}

    record Duplicate(String transactionId) implements ReceiveResult {}

    record InFlight(String transactionId) implements ReceiveResult {}

    record Rejected(List<String> errors) implements ReceiveResult {
        public Rejected {
            errors = List.copyOf(errors);
        }
    }

    record RateLimited(String clientId) implements ReceiveResult {}

    record Unavailable(String transactionId) implements ReceiveResult {}
}
