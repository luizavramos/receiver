package br.com.motorfraude.receiver.application;

import br.com.motorfraude.receiver.application.port.out.CommandPublisher;
import br.com.motorfraude.receiver.application.port.out.DuplicateGuard;
import br.com.motorfraude.receiver.application.port.out.DuplicateGuard.Reservation;
import br.com.motorfraude.receiver.application.port.out.PublishFailed;
import br.com.motorfraude.receiver.application.port.out.RateLimiter;
import br.com.motorfraude.receiver.application.port.out.TraceContext;
import br.com.motorfraude.receiver.domain.ReceiveResult;
import br.com.motorfraude.receiver.domain.TransactionCommand;

/**
 * Decide o desfecho de uma requisição (plan 001 §3). Em duas etapas, porque o limite de taxa precisa vir antes de ler e
 * validar o corpo:
 *
 * <ol>
 *   <li>{@link #admit}: a origem está dentro do limite? Cobrado uma vez por requisição.
 *   <li>{@link #execute}: reserva anti-duplicata em duas fases e publicação confirmada, para um comando já validado.
 * </ol>
 *
 * <p>A garantia de processamento único não está aqui: está no motor (ADR-10). O Redis só protege contra carga.
 */
public class ReceiveTransactionUseCase {

    private final RateLimiter rateLimiter;
    private final DuplicateGuard duplicateGuard;
    private final CommandPublisher publisher;

    public ReceiveTransactionUseCase(RateLimiter rateLimiter, DuplicateGuard duplicateGuard, CommandPublisher publisher) {
        this.rateLimiter = rateLimiter;
        this.duplicateGuard = duplicateGuard;
        this.publisher = publisher;
    }

    /** @return false se a origem excedeu o limite (responder 429) */
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
                // segue: UNAVAILABLE é fail-open (o motor resolve a duplicata)
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
