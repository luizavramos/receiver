package br.com.motorfraude.receiver.application.gateway;

import br.com.motorfraude.receiver.domain.TransactionCommand;

/** Publica o comando em transaction-commands.v1 e só retorna depois da confirmação (acks=all). */
public interface CommandPublisher {

    void publish(TransactionCommand command, TraceContext trace) throws PublishFailed;
}
