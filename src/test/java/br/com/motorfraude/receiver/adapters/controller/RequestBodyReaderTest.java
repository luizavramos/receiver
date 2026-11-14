package br.com.motorfraude.receiver.adapters.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Leitura do corpo com limite: nunca carrega um corpo gigante na memória. */
class RequestBodyReaderTest {

    private final RequestBodyReader reader = new RequestBodyReader(16);

    private static MockHttpServletRequest requestWith(int bytes) {
        var request = new MockHttpServletRequest();
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    @DisplayName("corpo dentro do limite: devolve os bytes")
    void dentro() throws Exception {
        assertThat(reader.read(requestWith(10))).hasValueSatisfying(b -> assertThat(b).hasSize(10));
    }

    @Test
    @DisplayName("corpo exatamente no limite ainda passa")
    void noLimite() throws Exception {
        assertThat(reader.read(requestWith(16))).hasValueSatisfying(b -> assertThat(b).hasSize(16));
    }

    @Test
    @DisplayName("um byte acima do limite: vazio")
    void acima() throws Exception {
        assertThat(reader.read(requestWith(17))).isEmpty();
    }

    @Test
    @DisplayName("sem corpo: devolve vazio de bytes, não 'grande demais'")
    void semCorpo() throws Exception {
        assertThat(reader.read(requestWith(0))).hasValueSatisfying(b -> assertThat(b).isEmpty());
    }

    @Test
    @DisplayName("o limite padrão é 16 KB")
    void padrao() {
        assertThat(RequestBodyReader.DEFAULT_MAX_BYTES).isEqualTo(16 * 1024);
        assertThat(new RequestBodyReader().maxBytes()).isEqualTo(16 * 1024);
    }
}
