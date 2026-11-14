package br.com.motorfraude.receiver.fakes;

import br.com.motorfraude.receiver.application.gateway.CommandPublisher;
import br.com.motorfraude.receiver.application.gateway.PublishFailed;
import br.com.motorfraude.receiver.application.gateway.TraceContext;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import java.util.ArrayList;
import java.util.List;

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
