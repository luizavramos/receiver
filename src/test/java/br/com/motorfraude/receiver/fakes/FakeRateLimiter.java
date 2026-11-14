package br.com.motorfraude.receiver.fakes;

import br.com.motorfraude.receiver.application.port.out.RateLimiter;
import java.util.ArrayList;
import java.util.List;

/** Limitador em memória: permite ou nega tudo, e registra quem foi consultado. */
public class FakeRateLimiter implements RateLimiter {

    public boolean allow = true;
    public final List<String> asked = new ArrayList<>();

    @Override
    public boolean tryAcquire(String clientId) {
        asked.add(clientId);
        return allow;
    }
}
