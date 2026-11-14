package br.com.motorfraude.receiver.application.gateway;

public class PublishFailed extends Exception {

    public PublishFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
