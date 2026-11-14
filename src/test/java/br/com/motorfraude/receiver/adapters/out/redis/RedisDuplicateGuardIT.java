package br.com.motorfraude.receiver.adapters.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.receiver.application.port.out.DuplicateGuard.Reservation;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T113 — o escudo em duas fases contra um Redis real: PENDING → DONE, expiração, concorrência e fail-open. */
@Testcontainers
class RedisDuplicateGuardIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12").withExposedPorts(6379);

    private static ResilientRedisConnection connection;
    private static RedisClient inspectorClient;
    private static RedisCommands<String, String> inspector;
    private static RedisDuplicateGuard guard;

    @BeforeAll
    static void setUp() throws Exception {
        // Timeout folgado: o de 5 ms (spec) é exercitado nos testes de pausa, onde a falha é certa
        connection = ResilientRedisConnection.create(REDIS.getHost(), REDIS.getMappedPort(6379), Duration.ofMillis(500));
        guard = new RedisDuplicateGuard(connection, Duration.ofSeconds(5), Duration.ofSeconds(600), RedisDuplicateGuard.Listener.NONE);
        awaitConnected(connection);
        inspectorClient = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        inspector = inspectorClient.connect().sync();
    }

    @AfterAll
    static void tearDown() {
        connection.close();
        inspectorClient.shutdown();
    }

    static void awaitConnected(ResilientRedisConnection conn) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!conn.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(conn.isConnected()).as("conexão com o Redis").isTrue();
    }

    private static String newId() {
        return "tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Test
    @DisplayName("reserva nova grava PENDING com TTL de até 5 s")
    void reservaGravaPending() {
        var id = newId();

        assertThat(guard.tryReserve(id)).isEqualTo(Reservation.RESERVED);

        assertThat(inspector.get("tx:" + id)).isEqualTo("PENDING");
        assertThat(inspector.ttl("tx:" + id)).isBetween(1L, 5L);
    }

    @Test
    @DisplayName("segunda chamada antes do confirm → IN_FLIGHT (nunca ALREADY_SEEN)")
    void antesDoConfirm() {
        var id = newId();
        guard.tryReserve(id);

        assertThat(guard.tryReserve(id)).isEqualTo(Reservation.IN_FLIGHT);
    }

    @Test
    @DisplayName("depois do confirm: DONE com TTL de até 600 s, e a próxima chamada → ALREADY_SEEN")
    void depoisDoConfirm() {
        var id = newId();
        guard.tryReserve(id);

        guard.confirm(id);

        assertThat(inspector.get("tx:" + id)).isEqualTo("DONE");
        assertThat(inspector.ttl("tx:" + id)).isBetween(6L, 600L);
        assertThat(guard.tryReserve(id)).isEqualTo(Reservation.ALREADY_SEEN);
    }

    @Test
    @DisplayName("release apaga a reserva: o retry volta a ser RESERVED")
    void release() {
        var id = newId();
        guard.tryReserve(id);

        guard.release(id);

        assertThat(inspector.get("tx:" + id)).isNull();
        assertThat(guard.tryReserve(id)).isEqualTo(Reservation.RESERVED);
    }

    @Test
    @DisplayName("PENDING expira sozinho (processo que morreu no meio): o retry volta a ser RESERVED")
    void pendingExpira() throws Exception {
        var shortLived = new RedisDuplicateGuard(
                connection, Duration.ofSeconds(1), Duration.ofSeconds(600), RedisDuplicateGuard.Listener.NONE);
        var id = newId();
        assertThat(shortLived.tryReserve(id)).isEqualTo(Reservation.RESERVED);

        Thread.sleep(1500);

        assertThat(shortLived.tryReserve(id)).isEqualTo(Reservation.RESERVED);
    }

    @Test
    @DisplayName("50 reservas simultâneas do mesmo id: exatamente uma RESERVED (SET NX é atômico)")
    void concorrencia() throws Exception {
        var id = newId();
        var start = new CountDownLatch(1);
        var results = new CopyOnWriteArrayList<Reservation>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    results.add(guard.tryReserve(id));
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        }

        assertThat(results).hasSize(50);
        assertThat(results.stream().filter(r -> r == Reservation.RESERVED)).hasSize(1);
        assertThat(results.stream().filter(r -> r == Reservation.IN_FLIGHT)).hasSize(49);
    }

    @Test
    @DisplayName("Redis lento (pausado) com timeout de 5 ms → UNAVAILABLE rápido, e volta ao normal depois")
    void redisLentoViraIndisponivel() throws Exception {
        var reasons = new CopyOnWriteArrayList<String>();
        var listener = new RedisDuplicateGuard.Listener() {
            @Override
            public void failOpen(String reason) {
                reasons.add(reason);
            }
        };
        try (var fast = ResilientRedisConnection.create(REDIS.getHost(), REDIS.getMappedPort(6379), Duration.ofMillis(5))) {
            awaitConnected(fast);
            var strict = new RedisDuplicateGuard(fast, Duration.ofSeconds(5), Duration.ofSeconds(600), listener);
            var id = newId();

            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                var started = System.nanoTime();
                var result = strict.tryReserve(id);
                var elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertThat(result).isEqualTo(Reservation.UNAVAILABLE);
                assertThat(elapsed).isLessThan(Duration.ofMillis(500));
                assertThat(reasons).containsExactly("timeout");
            } finally {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }
        }
    }

    @Test
    @DisplayName("Redis inalcançável desde o início: UNAVAILABLE, sem exceção; confirm e release não lançam")
    void redisInalcancavel() {
        try (var dead = ResilientRedisConnection.create("localhost", 1, Duration.ofMillis(5))) {
            var deadGuard = new RedisDuplicateGuard(dead, Duration.ofSeconds(5), Duration.ofSeconds(600), RedisDuplicateGuard.Listener.NONE);

            assertThat(deadGuard.tryReserve(newId())).isEqualTo(Reservation.UNAVAILABLE);
            deadGuard.confirm(newId());
            deadGuard.release(newId());
        }
    }
}
