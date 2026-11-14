package br.com.motorfraude.receiver.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.contracts.Channel;
import br.com.motorfraude.contracts.Counterparty;
import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.Money;
import br.com.motorfraude.contracts.TransactionType;
import br.com.motorfraude.receiver.application.port.out.DuplicateGuard.Reservation;
import br.com.motorfraude.receiver.application.port.out.PublishFailed;
import br.com.motorfraude.receiver.application.port.out.TraceContext;
import br.com.motorfraude.receiver.domain.ReceiveResult;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import br.com.motorfraude.receiver.fakes.FakeCommandPublisher;
import br.com.motorfraude.receiver.fakes.FakeDuplicateGuard;
import br.com.motorfraude.receiver.fakes.FakeRateLimiter;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T106 — as saídas do caso de uso, com portas em memória (plan §3, fluxo do caso de uso). */
class ReceiveTransactionUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-13T21:30:00Z");
    private static final String CLIENT = "pix-core";
    private static final TraceContext TRACE =
            new TraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    private final FakeRateLimiter rateLimiter = new FakeRateLimiter();
    private final FakeDuplicateGuard guard = new FakeDuplicateGuard();
    private final FakeCommandPublisher publisher = new FakeCommandPublisher();
    private ReceiveTransactionUseCase useCase;
    private TransactionCommand command;

    @BeforeEach
    void setUp() {
        useCase = new ReceiveTransactionUseCase(rateLimiter, guard, publisher);
        command = TransactionCommand.of(
                new EvaluateTransaction(
                        1,
                        "tx_20261113_0001",
                        "acc_tok_00000001",
                        "cus_tok_00000001",
                        TransactionType.PIX,
                        new Money(15_000, "BRL"),
                        new Counterparty("acc_tok_00004711", "60701190"),
                        Channel.MOBILE,
                        "dev_0f3a9c1d5b7e2a64",
                        NOW),
                NOW);
    }

    @Test
    @DisplayName("aceito: reserva, publica com o trace, confirma (PENDING para DONE) e responde Accepted")
    void aceito() {
        var result = useCase.execute(command, CLIENT, TRACE);

        assertThat(result).isEqualTo(new ReceiveResult.Accepted("tx_20261113_0001"));
        assertThat(publisher.published).containsExactly(command);
        assertThat(publisher.traces).containsExactly(TRACE);
        assertThat(guard.confirmed).containsExactly("tx_20261113_0001");
        assertThat(guard.released).isEmpty();
    }

    @Test
    @DisplayName("duplicata confirmada (DONE): Duplicate, sem publicar e sem confirmar")
    void duplicata() {
        guard.nextReservation = Reservation.ALREADY_SEEN;

        var result = useCase.execute(command, CLIENT, TRACE);

        assertThat(result).isEqualTo(new ReceiveResult.Duplicate("tx_20261113_0001"));
        assertThat(publisher.published).isEmpty();
        assertThat(guard.confirmed).isEmpty();
        assertThat(guard.released).isEmpty();
    }

    @Test
    @DisplayName("em andamento (PENDING): InFlight, nunca Duplicate; nao publica e nao apaga a reserva alheia")
    void emAndamento() {
        guard.nextReservation = Reservation.IN_FLIGHT;

        var result = useCase.execute(command, CLIENT, TRACE);

        assertThat(result).isEqualTo(new ReceiveResult.InFlight("tx_20261113_0001"));
        assertThat(publisher.published).isEmpty();
        assertThat(guard.confirmed).isEmpty();
        assertThat(guard.released).isEmpty();
    }

    @Test
    @DisplayName("admit: origem dentro do limite é admitida; acima do limite, não (antes de qualquer outra coisa)")
    void admissao() {
        assertThat(useCase.admit(CLIENT)).isTrue();

        rateLimiter.allow = false;

        assertThat(useCase.admit(CLIENT)).isFalse();
        assertThat(rateLimiter.asked).containsExactly(CLIENT, CLIENT);
        assertThat(guard.reserved).isEmpty();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("execute não consulta o limitador: o limite é cobrado uma vez, na admissão, antes de ler o corpo")
    void executeNaoCobraLimite() {
        rateLimiter.allow = false;

        var result = useCase.execute(command, CLIENT, TRACE);

        assertThat(result).isEqualTo(new ReceiveResult.Accepted("tx_20261113_0001"));
        assertThat(rateLimiter.asked).isEmpty();
    }

    @Test
    @DisplayName("Redis indisponivel (fail-open): publica e responde Accepted, sem confirmar e sem apagar")
    void redisIndisponivel() {
        guard.nextReservation = Reservation.UNAVAILABLE;

        var result = useCase.execute(command, CLIENT, TRACE);

        assertThat(result).isEqualTo(new ReceiveResult.Accepted("tx_20261113_0001"));
        assertThat(publisher.published).containsExactly(command);
        assertThat(guard.confirmed).isEmpty();
        assertThat(guard.released).isEmpty();
    }

    @Test
    @DisplayName("falha de publicacao com reserva propria: Unavailable, apaga a reserva e nao confirma")
    void falhaDePublicacaoReservado() {
        publisher.failing = true;

        var result = useCase.execute(command, CLIENT, TRACE);

        assertThat(result).isEqualTo(new ReceiveResult.Unavailable("tx_20261113_0001"));
        assertThat(guard.released).containsExactly("tx_20261113_0001");
        assertThat(guard.confirmed).isEmpty();
    }

    @Test
    @DisplayName("falha de publicacao com Redis indisponivel: Unavailable e nada a apagar")
    void falhaDePublicacaoSemRedis() {
        guard.nextReservation = Reservation.UNAVAILABLE;
        publisher.failing = true;

        var result = useCase.execute(command, CLIENT, TRACE);

        assertThat(result).isEqualTo(new ReceiveResult.Unavailable("tx_20261113_0001"));
        assertThat(guard.released).isEmpty();
        assertThat(guard.confirmed).isEmpty();
    }

    @Test
    @DisplayName("ordem das chamadas: reserva, publicacao, confirmacao")
    void ordem() {
        var events = new ArrayList<String>();
        var orderedGuard = new FakeDuplicateGuard() {
            @Override
            public Reservation tryReserve(String id) {
                events.add("reserve");
                return super.tryReserve(id);
            }

            @Override
            public void confirm(String id) {
                events.add("confirm");
                super.confirm(id);
            }
        };
        var orderedPublisher = new FakeCommandPublisher() {
            @Override
            public void publish(TransactionCommand c, TraceContext t) throws PublishFailed {
                events.add("publish");
                super.publish(c, t);
            }
        };

        new ReceiveTransactionUseCase(rateLimiter, orderedGuard, orderedPublisher).execute(command, CLIENT, TRACE);

        assertThat(events).containsExactly("reserve", "publish", "confirm");
    }
}
