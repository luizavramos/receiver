package br.com.motorfraude.receiver.application.gateway;

/** Limite de taxa por origem. */
public interface RateLimiter {

    boolean tryAcquire(String clientId);
}
