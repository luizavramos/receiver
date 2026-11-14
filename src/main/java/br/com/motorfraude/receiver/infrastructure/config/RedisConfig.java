package br.com.motorfraude.receiver.infrastructure.config;

import br.com.motorfraude.receiver.adapters.out.redis.RedisDuplicateGuard;
import br.com.motorfraude.receiver.adapters.out.redis.ResilientRedisConnection;
import br.com.motorfraude.receiver.application.port.out.DuplicateGuard;
import br.com.motorfraude.receiver.infrastructure.observability.ReceiverMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Redis como escudo de carga (ADR-10): conexão que não impede a subida e guard em duas fases. */
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
