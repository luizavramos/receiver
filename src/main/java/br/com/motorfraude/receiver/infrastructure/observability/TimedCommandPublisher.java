package br.com.motorfraude.receiver.infrastructure.observability;

import br.com.motorfraude.receiver.application.port.out.CommandPublisher;
import br.com.motorfraude.receiver.application.port.out.PublishFailed;
import br.com.motorfraude.receiver.application.port.out.TraceContext;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Mede o tempo da publicação (do envio ao ack do Kafka), com sucesso ou falha, em {@code receiver_publish_seconds}. É o
 * principal componente da latência do receiver, então o histograma tem buckets para o p99 do painel.
 */
public class TimedCommandPublisher implements CommandPublisher {

    private final CommandPublisher delegate;
    private final Timer timer;

    public TimedCommandPublisher(CommandPublisher delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.timer = Timer.builder("receiver.publish")
                .description("Tempo de publicação no Kafka até a confirmação (acks=all)")
                .publishPercentileHistogram()
                .register(registry);
    }

    @Override
    public void publish(TransactionCommand command, TraceContext trace) throws PublishFailed {
        var sample = Timer.start();
        try {
            delegate.publish(command, trace);
        } finally {
            sample.stop(timer);
        }
    }
}
