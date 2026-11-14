package br.com.motorfraude.receiver.application;

import br.com.motorfraude.receiver.application.gateway.CommandPublisher;
import br.com.motorfraude.receiver.application.gateway.DuplicateGuard;
import br.com.motorfraude.receiver.application.gateway.DuplicateGuard.Reservation;
import br.com.motorfraude.receiver.application.gateway.PublishFailed;
import br.com.motorfraude.receiver.application.gateway.RateLimiter;
import br.com.motorfraude.receiver.application.gateway.TraceContext;
import br.com.motorfraude.receiver.domain.ReceiveResult;
import br.com.motorfraude.receiver.domain.TransactionCommand;

public class ReceiveTransactionUseCase {

    private final RateLimiter rateLimiter;
    private final DuplicateGuard duplicateGuard;
    private final CommandPublisher publisher;

    public ReceiveTransactionUseCase(RateLimiter rateLimiter, DuplicateGuard duplicateGuard, CommandPublisher publisher) {
        this.rateLimiter = rateLimiter;
        this.duplicateGuard = duplicateGuard;
        this.publisher = publisher;
    }

    public boolean admit(String clientId) {
        return rateLimiter.tryAcquire(clientId);
    }

    public ReceiveResult execute(TransactionCommand command, String clientId, TraceContext trace) {
        var transactionId = command.transactionId();
        var reservation = duplicateGuard.tryReserve(transactionId);
        switch (reservation) {
            case ALREADY_SEEN -> {
                return new ReceiveResult.Duplicate(transactionId);
            }
            case IN_FLIGHT -> {
                return new ReceiveResult.InFlight(transactionId);
            }
            case RESERVED, UNAVAILABLE -> {
            }
        }

        try {
            publisher.publish(command, trace);
        } catch (PublishFailed e) {
            if (reservation == Reservation.RESERVED) {
                duplicateGuard.release(transactionId);
            }
            return new ReceiveResult.Unavailable(transactionId);
        }

        if (reservation == Reservation.RESERVED) {
            duplicateGuard.confirm(transactionId);
        }
        return new ReceiveResult.Accepted(transactionId);
    }
}
