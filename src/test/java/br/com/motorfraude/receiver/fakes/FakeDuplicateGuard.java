package br.com.motorfraude.receiver.fakes;

import br.com.motorfraude.receiver.application.port.out.DuplicateGuard;
import java.util.ArrayList;
import java.util.List;

/** Guarda em memória com resposta programável e registro das chamadas. */
public class FakeDuplicateGuard implements DuplicateGuard {

    public Reservation nextReservation = Reservation.RESERVED;
    public final List<String> reserved = new ArrayList<>();
    public final List<String> confirmed = new ArrayList<>();
    public final List<String> released = new ArrayList<>();

    @Override
    public Reservation tryReserve(String transactionId) {
        reserved.add(transactionId);
        return nextReservation;
    }

    @Override
    public void confirm(String transactionId) {
        confirmed.add(transactionId);
    }

    @Override
    public void release(String transactionId) {
        released.add(transactionId);
    }
}
