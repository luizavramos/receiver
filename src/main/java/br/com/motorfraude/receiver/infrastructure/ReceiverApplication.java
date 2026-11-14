package br.com.motorfraude.receiver.infrastructure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Ponto de entrada do receiver (spec 001). Varre todo o pacote base: adapters e infraestrutura. */
@SpringBootApplication(scanBasePackages = "br.com.motorfraude.receiver")
@ConfigurationPropertiesScan
public class ReceiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReceiverApplication.class, args);
    }
}
