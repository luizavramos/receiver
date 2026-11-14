package br.com.motorfraude.receiver.infrastructure.config;

import br.com.motorfraude.commons.time.ClockProvider;
import br.com.motorfraude.commons.tracing.TraceParent;
import br.com.motorfraude.receiver.adapters.in.rest.AllowedClients;
import br.com.motorfraude.receiver.adapters.in.rest.RequestValidator;
import br.com.motorfraude.receiver.adapters.in.rest.TraceContextResolver;
import br.com.motorfraude.receiver.application.ReceiveTransactionUseCase;
import br.com.motorfraude.receiver.application.port.out.CommandPublisher;
import br.com.motorfraude.receiver.application.port.out.DuplicateGuard;
import br.com.motorfraude.receiver.application.port.out.RateLimiter;
import br.com.motorfraude.receiver.infrastructure.observability.ReceiverMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Liga o caso de uso, o adapter REST e o relógio (a única fonte de "agora" do serviço). */
@Configuration
public class ReceiverConfig {

    @Bean
    Clock clock() {
        return ClockProvider.system();
    }

    @Bean
    AllowedClients allowedClients(ReceiverProperties properties) {
        return new AllowedClients(Set.copyOf(properties.clients()));
    }

    /** Um objeto só: métricas das requisições (adapter REST) e dos eventos do Redis (guard). */
    @Bean
    ReceiverMetrics receiverMetrics(MeterRegistry registry) {
        return new ReceiverMetrics(registry);
    }

    @Bean
    RequestValidator requestValidator() {
        return new RequestValidator();
    }

    /** O span ativo, lido pelo Micrometer Tracing: ele já leu o {@code traceparent} da requisição ou gerou um. */
    @Bean
    TraceContextResolver traceContextResolver(ObjectProvider<Tracer> tracer) {
        return new TraceContextResolver(() -> {
            var current = tracer.getIfAvailable();
            var span = current == null ? null : current.currentSpan();
            if (span == null) {
                return null;
            }
            var context = span.context();
            var flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
            return TraceParent.parse("00-" + context.traceId() + "-" + context.spanId() + "-" + flags).orElse(null);
        });
    }

    @Bean
    ReceiveTransactionUseCase receiveTransactionUseCase(
            RateLimiter rateLimiter, DuplicateGuard duplicateGuard, CommandPublisher publisher) {
        return new ReceiveTransactionUseCase(rateLimiter, duplicateGuard, publisher);
    }
}
