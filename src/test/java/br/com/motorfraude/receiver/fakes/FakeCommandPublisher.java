package br.com.motorfraude.receiver.fakes;

import br.com.motorfraude.receiver.application.port.out.CommandPublisher;
import br.com.motorfraude.receiver.application.port.out.PublishFailed;
import br.com.motorfraude.receiver.application.port.out.TraceContext;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import java.util.ArrayList;
import java.util.List;

/** Publicador em memória: registra o que "foi para o Kafka" e pode ser programado para falhar. */
public class FakeCommandPublisher implements CommandPublisher {

    public boolean failing;
    public final List<TransactionCommand> published = new ArrayList<>();
    public final List<TraceContext> traces = new ArrayList<>();

    @Override
    public void publish(TransactionCommand command, TraceContext trace) throws PublishFailed {
        if (failing) {
            throw new PublishFailed("kafka indisponível (fake)", new RuntimeException("timeout"));
        }
        published.add(command);
        traces.add(trace);
    }
}
