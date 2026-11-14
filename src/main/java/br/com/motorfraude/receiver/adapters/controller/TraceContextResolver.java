package br.com.motorfraude.receiver.adapters.controller;

import br.com.motorfraude.commons.tracing.TraceParent;
import br.com.motorfraude.receiver.application.gateway.TraceContext;
import java.util.function.Supplier;

public class TraceContextResolver {

    private final Supplier<TraceParent> currentSpan;

    public TraceContextResolver(Supplier<TraceParent> currentSpan) {
        this.currentSpan = currentSpan;
    }

    public TraceContext resolve(String incomingTraceparent) {
        var current = currentSpan.get();
        if (current != null) {
            return new TraceContext(current.format());
        }
        return TraceParent.parse(incomingTraceparent)
                .map(parent -> new TraceContext(parent.format()))
                .orElseGet(TraceContext::none);
    }
}
