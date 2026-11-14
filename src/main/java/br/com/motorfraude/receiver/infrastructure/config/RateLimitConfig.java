package br.com.motorfraude.receiver.infrastructure.config;

import br.com.motorfraude.receiver.adapters.out.ratelimit.Bucket4jRateLimiter;
import br.com.motorfraude.receiver.application.port.out.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Limite de requisições por origem e por instância (spec 001, FR-007). */
@Configuration
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(ReceiverProperties properties) {
        return new Bucket4jRateLimiter(properties.rateLimitPerSecond());
    }
}
