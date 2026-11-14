package br.com.motorfraude.receiver.infrastructure.config;

import br.com.motorfraude.receiver.adapters.gateway.ratelimit.Bucket4jRateLimiter;
import br.com.motorfraude.receiver.application.gateway.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(ReceiverProperties properties) {
        return new Bucket4jRateLimiter(properties.rateLimitPerSecond());
    }
}
