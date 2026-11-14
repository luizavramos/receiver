package br.com.motorfraude.receiver.application.port.out;

import br.com.motorfraude.receiver.domain.TransactionCommand;

/** Publica o comando em transaction-commands.v1 e só retorna depois da confirmação (acks=all). */
public interface CommandPublisher {

    /** @throws PublishFailed se o Kafka não confirmou dentro do prazo */
    void publish(TransactionCommand command, TraceContext trace) throws PublishFailed;
}
