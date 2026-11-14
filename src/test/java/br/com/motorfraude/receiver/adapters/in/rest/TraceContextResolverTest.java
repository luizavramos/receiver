package br.com.motorfraude.receiver.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.commons.tracing.TraceParent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T112 — de onde vem o contexto de trace que o receiver grava no Kafka. */
class TraceContextResolverTest {

    private static final TraceParent CURRENT =
            new TraceParent("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");
    private static final String INCOMING = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";

    @Test
    @DisplayName("com span ativo (Micrometer Tracing), usa o span: ele já leu ou gerou o traceparent da requisição")
    void spanAtivo() {
        var resolver = new TraceContextResolver(() -> CURRENT);

        assertThat(resolver.resolve(INCOMING).traceparent()).isEqualTo(CURRENT.format());
    }

    @Test
    @DisplayName("sem span ativo, o header recebido (válido) é propagado")
    void headerRecebido() {
        var resolver = new TraceContextResolver(() -> null);

        assertThat(resolver.resolve(INCOMING).traceparent()).isEqualTo(INCOMING);
    }

    @Test
    @DisplayName("sem span e sem header: sem contexto (o publicador começa um trace novo)")
    void nenhum() {
        var resolver = new TraceContextResolver(() -> null);

        assertThat(resolver.resolve(null).traceparent()).isNull();
    }

    @Test
    @DisplayName("header malformado é ignorado, nunca propagado")
    void malformado() {
        var resolver = new TraceContextResolver(() -> null);

        assertThat(resolver.resolve("lixo").traceparent()).isNull();
    }
}
