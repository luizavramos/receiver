package br.com.motorfraude.receiver.adapters.out.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T117 — limite por origem, em memória, com relógio controlado (sem dormir nos testes). */
class Bucket4jRateLimiterTest {

    /** Relógio de teste: o tempo só anda quando o teste manda. */
    private static final class ManualMeter implements TimeMeter {
        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    private final ManualMeter meter = new ManualMeter();
    private Bucket4jRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new Bucket4jRateLimiter(5, meter);
    }

    private int allowed(String client, int attempts) {
        int ok = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(client)) {
                ok++;
            }
        }
        return ok;
    }

    @Test
    @DisplayName("até o limite por segundo passa; acima disso, não")
    void excedeoLimite() {
        assertThat(allowed("pix-core", 5)).isEqualTo(5);
        assertThat(limiter.tryAcquire("pix-core")).isFalse();
    }

    @Test
    @DisplayName("origens isoladas: uma esgotar o limite não afeta as outras")
    void origensIsoladas() {
        allowed("pix-core", 10);

        assertThat(limiter.tryAcquire("pix-core")).isFalse();
        assertThat(allowed("ted-core", 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("recarrega com o tempo: depois de 1 s o limite volta inteiro")
    void recarrega() {
        allowed("pix-core", 5);

        meter.advance(Duration.ofSeconds(1));

        assertThat(allowed("pix-core", 10)).isEqualTo(5);
    }

    @Test
    @DisplayName("recarga gradual: 200 ms devolvem 1 permissão (5 por segundo)")
    void recargaGradual() {
        allowed("pix-core", 5);

        meter.advance(Duration.ofMillis(200));

        assertThat(allowed("pix-core", 5)).isEqualTo(1);
    }

    @Test
    @DisplayName("tempo parado não acumula além da capacidade (sem rajada gigante depois de ociosidade)")
    void naoAcumulaAlemDaCapacidade() {
        meter.advance(Duration.ofHours(1));

        assertThat(allowed("pix-core", 20)).isEqualTo(5);
    }

    @Test
    @DisplayName("limite inválido (zero ou negativo) é erro de configuração, não limite infinito")
    void limiteInvalido() {
        assertThatThrownBy(() -> new Bucket4jRateLimiter(0, meter)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bucket4jRateLimiter(-1, meter)).isInstanceOf(IllegalArgumentException.class);
    }
}
