package br.com.motorfraude.receiver.infrastructure.config;

import br.com.motorfraude.receiver.adapters.gateway.redis.RedisDuplicateGuard;
import br.com.motorfraude.receiver.adapters.gateway.redis.ResilientRedisConnection;
import br.com.motorfraude.receiver.application.gateway.DuplicateGuard;
import br.com.motorfraude.receiver.infrastructure.observability.ReceiverMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "close")
    ResilientRedisConnection redisConnection(ReceiverProperties properties) {
        var redis = properties.redis();
        return ResilientRedisConnection.create(redis.host(), redis.port(), redis.commandTimeout());
    }

    @Bean
    DuplicateGuard duplicateGuard(ResilientRedisConnection connection, ReceiverProperties properties, ReceiverMetrics metrics) {
        var redis = properties.redis();
        return new RedisDuplicateGuard(connection, redis.pendingTtl(), redis.doneTtl(), metrics);
    }
}
