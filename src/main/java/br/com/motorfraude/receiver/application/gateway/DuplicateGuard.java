package br.com.motorfraude.receiver.application.gateway;

public interface DuplicateGuard {

    enum Reservation {
        RESERVED,
        ALREADY_SEEN,
        IN_FLIGHT,
        UNAVAILABLE
    }

    Reservation tryReserve(String transactionId);

    void confirm(String transactionId);

    void release(String transactionId);
}
