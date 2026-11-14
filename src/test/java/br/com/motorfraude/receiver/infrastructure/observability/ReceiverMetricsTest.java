package br.com.motorfraude.receiver.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.motorfraude.contracts.Channel;
import br.com.motorfraude.contracts.Counterparty;
import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.Money;
import br.com.motorfraude.contracts.TransactionType;
import br.com.motorfraude.receiver.adapters.in.rest.Outcome;
import br.com.motorfraude.receiver.application.port.out.PublishFailed;
import br.com.motorfraude.receiver.application.port.out.TraceContext;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import br.com.motorfraude.receiver.fakes.FakeCommandPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T119 — os contadores e o histograma do plan §5 aumentam quando o evento correspondente acontece. */
class ReceiverMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReceiverMetrics metrics = new ReceiverMetrics(registry);

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("receiver.requests: um contador por desfecho e por origem")
    void requisicoesPorDesfechoEOrigem() {
        metrics.record(Outcome.ACCEPTED, "pix-core");
        metrics.record(Outcome.ACCEPTED, "pix-core");
        metrics.record(Outcome.ACCEPTED, "ted-core");
        metrics.record(Outcome.REJECTED, "pix-core");

        assertThat(count("receiver.requests", "status", "ACCEPTED", "client", "pix-core")).isEqualTo(2);
        assertThat(count("receiver.requests", "status", "ACCEPTED", "client", "ted-core")).isEqualTo(1);
        assertThat(count("receiver.requests", "status", "REJECTED", "client", "pix-core")).isEqualTo(1);
    }

    @Test
    @DisplayName("DUPLICATE também aumenta receiver.duplicates.blocked")
    void duplicatasBarradas() {
        metrics.record(Outcome.DUPLICATE, "pix-core");
        metrics.record(Outcome.ACCEPTED, "pix-core");

        assertThat(count("receiver.duplicates.blocked")).isEqualTo(1);
    }

    @Test
    @DisplayName("IN_FLIGHT também aumenta receiver.in.flight")
    void emAndamento() {
        metrics.record(Outcome.IN_FLIGHT, "pix-core");

        assertThat(count("receiver.in.flight")).isEqualTo(1);
    }

    @Test
    @DisplayName("fail-open do Redis: contador por motivo (timeout ou erro)")
    void failOpen() {
        metrics.failOpen("timeout");
        metrics.failOpen("timeout");
        metrics.failOpen("erro");

        assertThat(count("receiver.redis.failopen", "reason", "timeout")).isEqualTo(2);
        assertThat(count("receiver.redis.failopen", "reason", "erro")).isEqualTo(1);
    }

    @Test
    @DisplayName("falhas de confirm e de release no Redis têm contadores próprios")
    void confirmEReleaseFalhos() {
        metrics.confirmFailed();
        metrics.releaseFailed();
        metrics.releaseFailed();

        assertThat(count("receiver.redis.confirm.failures")).isEqualTo(1);
        assertThat(count("receiver.redis.release.failures")).isEqualTo(2);
    }

    private TransactionCommand command() {
        var now = Instant.parse("2026-11-13T21:30:00Z");
        return TransactionCommand.of(
                new EvaluateTransaction(1, "tx_20261113_0001", "acc_tok_00000001", "cus_tok_00000001",
                        TransactionType.PIX, new Money(100, "BRL"), new Counterparty("acc_tok_00004711", "60701190"),
                        Channel.MOBILE, "dev_0f3a9c1d5b7e2a64", now),
                now);
    }

    @Test
    @DisplayName("receiver.publish: o histograma conta publicações com sucesso e com falha, e repassa a exceção")
    void tempoDePublicacao() throws Exception {
        var delegate = new FakeCommandPublisher();
        var timed = new TimedCommandPublisher(delegate, registry);

        timed.publish(command(), TraceContext.none());
        delegate.failing = true;
        assertThatThrownBy(() -> timed.publish(command(), TraceContext.none())).isInstanceOf(PublishFailed.class);

        assertThat(registry.get("receiver.publish").timer().count()).isEqualTo(2);
        assertThat(delegate.published).hasSize(1);
    }
}
