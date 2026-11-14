package br.com.motorfraude.receiver.fakes;

import br.com.motorfraude.receiver.adapters.presenter.Outcome;
import br.com.motorfraude.receiver.adapters.controller.RequestMetrics;
import java.util.ArrayList;
import java.util.List;

/** Registra os desfechos que o controller reportaria às métricas. */
public class FakeRequestMetrics implements RequestMetrics {

    public record Recorded(Outcome outcome, String client) {}

    public final List<Recorded> recorded = new ArrayList<>();

    @Override
    public void record(Outcome outcome, String client) {
        recorded.add(new Recorded(outcome, client));
    }
}
