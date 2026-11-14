package br.com.motorfraude.receiver.application.gateway;

import br.com.motorfraude.receiver.domain.TransactionCommand;

public interface CommandPublisher {

    void publish(TransactionCommand command, TraceContext trace) throws PublishFailed;
}
