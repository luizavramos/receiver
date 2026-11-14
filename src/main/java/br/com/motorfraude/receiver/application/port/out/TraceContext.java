package br.com.motorfraude.receiver.application.port.out;

/** Contexto de rastreio da requisição, no formato W3C {@code traceparent}. Nulo se não houver. */
public record TraceContext(String traceparent) {

    public static TraceContext none() {
        return new TraceContext(null);
    }
}
