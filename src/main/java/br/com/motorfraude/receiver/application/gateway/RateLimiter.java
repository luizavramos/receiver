package br.com.motorfraude.receiver.application.gateway;

/** Limite de taxa por origem. */
public interface RateLimiter {

    /** @return false se a origem excedeu o limite */
    boolean tryAcquire(String clientId);
}
