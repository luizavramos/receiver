package br.com.motorfraude.receiver.adapters.out.ratelimit;

import br.com.motorfraude.receiver.application.port.out.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite por origem, em memória: um balde por {@code X-Client-Id}, com capacidade e recarga de {@code permitsPerSecond}.
 * O limite é por instância; o limite global aproximado é esse valor vezes o número de instâncias (spec 001, premissas).
 *
 * <p>Os baldes só nascem para origens autorizadas (o controller responde 401 antes de limitar), então o mapa tem tamanho
 * limitado pela lista {@code receiver.clients}.
 */
public class Bucket4jRateLimiter implements RateLimiter {

    private final long permitsPerSecond;
    private final TimeMeter timeMeter;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public Bucket4jRateLimiter(long permitsPerSecond) {
        this(permitsPerSecond, TimeMeter.SYSTEM_NANOTIME);
    }

    /** Construtor para testes: o relógio é injetado, para não dormir. */
    Bucket4jRateLimiter(long permitsPerSecond, TimeMeter timeMeter) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("o limite por segundo deve ser positivo: " + permitsPerSecond);
        }
        this.permitsPerSecond = permitsPerSecond;
        this.timeMeter = timeMeter;
    }

    @Override
    public boolean tryAcquire(String clientId) {
        return buckets.computeIfAbsent(clientId, id -> newBucket()).tryConsume(1);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(permitsPerSecond)
                        .refillGreedy(permitsPerSecond, Duration.ofSeconds(1))
                        .build())
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
