package br.com.motorfraude.receiver.application.gateway;

public interface RateLimiter {

    boolean tryAcquire(String clientId);
}
