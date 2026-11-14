package br.com.motorfraude.receiver.adapters.out.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Conexão com o Redis que nunca impede o serviço de subir: o receiver é fail-open (ADR-10), então Redis fora do ar na
 * partida não pode derrubar a entrada. A primeira conexão (e as novas tentativas, a cada 1 s) acontece em segundo plano;
 * depois de conectada, o Lettuce reconecta sozinho. Enquanto não há conexão, {@link #commands()} devolve nulo.
 *
 * <p>Comandos têm timeout curto e, desconectado, são rejeitados na hora (nada fica enfileirado esperando o Redis).
 */
public final class ResilientRedisConnection implements AutoCloseable {

    private static final long RETRY_MILLIS = 1000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    private final RedisClient client;
    private final RedisURI uri;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "redis-connector");
                thread.setDaemon(true);
                return thread;
            });
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile RedisCommands<String, String> commands;
    private volatile boolean closed;

    private ResilientRedisConnection(RedisClient client, RedisURI uri) {
        this.client = client;
        this.uri = uri;
    }

    /** @param commandTimeout prazo de cada comando; o valor da spec é 5 ms */
    public static ResilientRedisConnection create(String host, int port, Duration commandTimeout) {
        var uri = RedisURI.builder().withHost(host).withPort(port).withTimeout(commandTimeout).build();
        var client = RedisClient.create();
        client.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
                .build());
        var resilient = new ResilientRedisConnection(client, uri);
        resilient.scheduler.execute(resilient::attempt);
        return resilient;
    }

    private void attempt() {
        if (closed) {
            return;
        }
        try {
            var opened = client.connect(uri);
            connection = opened;
            commands = opened.sync();
        } catch (RuntimeException e) {
            if (!closed) {
                scheduler.schedule(this::attempt, RETRY_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** @return os comandos, ou nulo se ainda não houve conexão */
    public RedisCommands<String, String> commands() {
        return commands;
    }

    public boolean isConnected() {
        var current = connection;
        return current != null && current.isOpen();
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        var current = connection;
        if (current != null) {
            current.closeAsync();
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
    }
}
