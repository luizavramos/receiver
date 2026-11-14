package br.com.motorfraude.receiver.domain;

import java.util.List;

public class InvalidTransactionException extends RuntimeException {

    private final List<String> violations;

    public InvalidTransactionException(List<String> violations) {
        super(String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
