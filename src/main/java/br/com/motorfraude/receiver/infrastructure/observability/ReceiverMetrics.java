package br.com.motorfraude.receiver.infrastructure.observability;

import br.com.motorfraude.receiver.adapters.in.rest.Outcome;
import br.com.motorfraude.receiver.adapters.in.rest.RequestMetrics;
import br.com.motorfraude.receiver.adapters.out.redis.RedisDuplicateGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Métricas do receiver (plan 001 §5). Nomes Micrometer; no Prometheus viram {@code receiver_requests_total},
 * {@code receiver_duplicates_blocked_total}, {@code receiver_in_flight_total}, {@code receiver_redis_failopen_total},
 * {@code receiver_redis_release_failures_total} e {@code receiver_redis_confirm_failures_total}.
 *
 * <p>Os labels têm valores em conjuntos fechados: desfecho (enum), origem (lista autorizada + {@code unknown}) e motivo
 * do fail-open ({@code timeout}/{@code erro}).
 */
public class ReceiverMetrics implements RequestMetrics, RedisDuplicateGuard.Listener {

    private final MeterRegistry registry;
    private final Counter duplicatesBlocked;
    private final Counter inFlight;
    private final Counter confirmFailures;
    private final Counter releaseFailures;

    public ReceiverMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.duplicatesBlocked = Counter.builder("receiver.duplicates.blocked")
                .description("Reenvios barrados como duplicata (chave DONE no Redis)")
                .register(registry);
        this.inFlight = Counter.builder("receiver.in.flight")
                .description("Reenvios recusados porque a mesma transação ainda estava sendo publicada")
                .register(registry);
        this.confirmFailures = Counter.builder("receiver.redis.confirm.failures")
                .description("Falhas ao marcar a transação como confirmada (DONE) no Redis")
                .register(registry);
        this.releaseFailures = Counter.builder("receiver.redis.release.failures")
                .description("Falhas ao apagar a reserva no Redis depois de uma publicação que falhou")
                .register(registry);
    }

    @Override
    public void record(Outcome outcome, String client) {
        Counter.builder("receiver.requests")
                .description("Requisições recebidas, por desfecho e origem")
                .tag("status", outcome.name())
                .tag("client", client)
                .register(registry)
                .increment();
        if (outcome == Outcome.DUPLICATE) {
            duplicatesBlocked.increment();
        } else if (outcome == Outcome.IN_FLIGHT) {
            inFlight.increment();
        }
    }

    @Override
    public void failOpen(String reason) {
        Counter.builder("receiver.redis.failopen")
                .description("Vezes em que o Redis não respondeu e o receiver seguiu sem o escudo (fail-open)")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    @Override
    public void confirmFailed() {
        confirmFailures.increment();
    }

    @Override
    public void releaseFailed() {
        releaseFailures.increment();
    }
}
