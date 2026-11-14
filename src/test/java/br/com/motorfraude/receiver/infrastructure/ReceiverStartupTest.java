package br.com.motorfraude.receiver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/** T101: o serviço sobe com a configuração local, expõe health (liveness/readiness) e métricas Prometheus. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiverStartupTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("sem Kafka: o serviço sobe e está vivo (200), mas não está pronto (503): readiness exige o produtor Kafka")
    void health() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("/actuator/prometheus expõe métricas da JVM")
    void metricas() throws Exception {
        var response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("application.yml: porta 8081 (o teste usa porta aleatória) e virtual threads (plan §2)")
    void configuracaoPadrao() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var props = yaml.getObject();

        assertThat(props).isNotNull();
        assertThat(props.getProperty("server.port")).isEqualTo("8081");
        assertThat(props.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
    }
}
