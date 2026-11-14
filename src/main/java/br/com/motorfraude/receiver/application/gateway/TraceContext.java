package br.com.motorfraude.receiver.application.gateway;

public record TraceContext(String traceparent) {

    public static TraceContext none() {
        return new TraceContext(null);
    }
}
