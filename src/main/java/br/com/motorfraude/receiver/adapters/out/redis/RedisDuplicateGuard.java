package br.com.motorfraude.receiver.adapters.out.redis;

import br.com.motorfraude.receiver.application.port.out.DuplicateGuard;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.SetArgs;
import java.time.Duration;

/**
 * Escudo de carga no Redis, em duas fases (spec 001, FR-004):
 *
 * <ul>
 *   <li>{@code tryReserve}: {@code SET tx:{id} PENDING NX EX 5}. Se a chave já existe, lê o valor: {@code DONE} vira
 *       {@code ALREADY_SEEN}; {@code PENDING} (ou sumiu entre o SET e o GET) vira {@code IN_FLIGHT}.
 *   <li>{@code confirm}: {@code SET tx:{id} DONE EX 600}, depois do ack do Kafka.
 *   <li>{@code release}: {@code DEL}, quando a publicação falhou.
 * </ul>
 *
 * <p>Redis fora do ar, lento (estourou o timeout) ou desconectado vira {@code UNAVAILABLE}: o receiver segue sem o escudo
 * (fail-open). Nenhum método lança exceção.
 */
public class RedisDuplicateGuard implements DuplicateGuard {

    static final String PENDING = "PENDING";
    static final String DONE = "DONE";
    private static final String KEY_PREFIX = "tx:";

    /** Observador de eventos para métricas (T119). Os métodos padrão não fazem nada. */
    public interface Listener {
        Listener NONE = new Listener() {};

        /** O Redis não respondeu; {@code reason} é {@code timeout} ou {@code erro}. */
        default void failOpen(String reason) {}

        default void confirmFailed() {}

        default void releaseFailed() {}
    }

    private final ResilientRedisConnection connection;
    private final long pendingTtlSeconds;
    private final long doneTtlSeconds;
    private final Listener listener;

    public RedisDuplicateGuard(ResilientRedisConnection connection, Duration pendingTtl, Duration doneTtl, Listener listener) {
        this.connection = connection;
        this.pendingTtlSeconds = pendingTtl.toSeconds();
        this.doneTtlSeconds = doneTtl.toSeconds();
        this.listener = listener;
    }

    @Override
    public Reservation tryReserve(String transactionId) {
        var commands = connection.commands();
        if (commands == null) {
            listener.failOpen("erro");
            return Reservation.UNAVAILABLE;
        }
        var key = key(transactionId);
        try {
            if ("OK".equals(commands.set(key, PENDING, SetArgs.Builder.nx().ex(pendingTtlSeconds)))) {
                return Reservation.RESERVED;
            }
            return DONE.equals(commands.get(key)) ? Reservation.ALREADY_SEEN : Reservation.IN_FLIGHT;
        } catch (RuntimeException e) {
            listener.failOpen(e instanceof RedisCommandTimeoutException ? "timeout" : "erro");
            return Reservation.UNAVAILABLE;
        }
    }

    @Override
    public void confirm(String transactionId) {
        var commands = connection.commands();
        try {
            if (commands == null) {
                throw new IllegalStateException("sem conexão");
            }
            commands.set(key(transactionId), DONE, SetArgs.Builder.ex(doneTtlSeconds));
        } catch (RuntimeException e) {
            listener.confirmFailed();
        }
    }

    @Override
    public void release(String transactionId) {
        var commands = connection.commands();
        try {
            if (commands == null) {
                throw new IllegalStateException("sem conexão");
            }
            commands.del(key(transactionId));
        } catch (RuntimeException e) {
            listener.releaseFailed();
        }
    }

    private static String key(String transactionId) {
        return KEY_PREFIX + transactionId;
    }
}
