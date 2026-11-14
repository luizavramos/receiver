package br.com.motorfraude.receiver.infrastructure.observability;

import br.com.motorfraude.receiver.application.gateway.CommandPublisher;
import br.com.motorfraude.receiver.application.gateway.PublishFailed;
import br.com.motorfraude.receiver.application.gateway.TraceContext;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
