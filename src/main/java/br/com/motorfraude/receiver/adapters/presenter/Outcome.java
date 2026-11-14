package br.com.motorfraude.receiver.adapters.presenter;

public enum Outcome {
    ACCEPTED,
    DUPLICATE,
    IN_FLIGHT,
    REJECTED,
    RATE_LIMITED,
    UNAVAILABLE,
    UNAUTHORIZED,
    TOO_LARGE
}
