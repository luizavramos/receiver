package br.com.motorfraude.receiver.application.port.out;

/** O Kafka não confirmou a gravação. A transação NÃO foi aceita. */
public class PublishFailed extends Exception {

    public PublishFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
