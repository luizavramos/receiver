package br.com.motorfraude.receiver.infrastructure.config;

import br.com.motorfraude.commons.time.ClockProvider;
import br.com.motorfraude.commons.tracing.TraceParent;
import br.com.motorfraude.receiver.adapters.controller.AllowedClients;
import br.com.motorfraude.receiver.adapters.controller.RequestBodyReader;
import br.com.motorfraude.receiver.adapters.controller.RequestValidator;
import br.com.motorfraude.receiver.adapters.controller.TraceContextResolver;
import br.com.motorfraude.receiver.adapters.controller.TransactionRequestParser;
import br.com.motorfraude.receiver.adapters.presenter.TransactionPresenter;
import br.com.motorfraude.receiver.application.ReceiveTransactionUseCase;
import br.com.motorfraude.receiver.application.gateway.CommandPublisher;
import br.com.motorfraude.receiver.application.gateway.DuplicateGuard;
import br.com.motorfraude.receiver.application.gateway.RateLimiter;
import br.com.motorfraude.receiver.infrastructure.observability.ReceiverMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    ReceiverMetrics receiverMetrics(MeterRegistry registry) {
        return new ReceiverMetrics(registry);
    }

    @Bean
    RequestValidator requestValidator() {
        return new RequestValidator();
    }

    @Bean
    RequestBodyReader requestBodyReader() {
        return new RequestBodyReader();
    }

    @Bean
    TransactionRequestParser transactionRequestParser(RequestValidator validator, Clock clock) {
        return new TransactionRequestParser(validator, clock);
    }

    @Bean
    TransactionPresenter transactionPresenter() {
        return new TransactionPresenter();
    }

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
