package br.com.motorfraude.receiver.fakes;

import br.com.motorfraude.receiver.application.gateway.RateLimiter;
import java.util.ArrayList;
import java.util.List;

public class FakeRateLimiter implements RateLimiter {

    public boolean allow = true;
    public final List<String> asked = new ArrayList<>();

    @Override
    public boolean tryAcquire(String clientId) {
        asked.add(clientId);
        return allow;
    }
}
