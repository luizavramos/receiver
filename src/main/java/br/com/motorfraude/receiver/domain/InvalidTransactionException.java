package br.com.motorfraude.receiver.domain;

import java.util.List;

/** Violação de invariante de domínio. Carrega todas as violações, uma por campo, para o 400 listar tudo. */
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
