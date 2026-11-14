package br.com.motorfraude.receiver.adapters.out.kafka;

import br.com.motorfraude.commons.tracing.KafkaTracePropagation;
import br.com.motorfraude.commons.tracing.StandardHeaders;
import br.com.motorfraude.commons.tracing.TraceParent;
import br.com.motorfraude.contracts.JsonCodec;
import br.com.motorfraude.receiver.application.port.out.CommandPublisher;
import br.com.motorfraude.receiver.application.port.out.PublishFailed;
import br.com.motorfraude.receiver.application.port.out.TraceContext;
import br.com.motorfraude.receiver.domain.TransactionCommand;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;

/**
 * Publica o comando em {@code transaction-commands.v1} e espera a confirmação (acks=all, produtor idempotente): só
 * retorna quando o Kafka gravou. Chave = accountId, para manter a ordem por conta.
 *
 * <p>Headers (000 plan §3.2): {@code traceparent}, {@code receivedAt}, {@code schemaVersion=1} e
 * {@code commandType=EvaluateTransaction}. Com threads virtuais, bloquear em {@code get()} não ocupa thread de plataforma.
 */
public class KafkaCommandPublisher implements CommandPublisher {

    static final String COMMAND_TYPE = "EvaluateTransaction";
    static final String SCHEMA_VERSION = "1";

    private final Producer<String, byte[]> producer;
    private final String topic;
    private final Clock clock;
    private final Duration timeout;

    public KafkaCommandPublisher(Producer<String, byte[]> producer, String topic, Clock clock, Duration timeout) {
        this.producer = producer;
        this.topic = topic;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public void publish(TransactionCommand command, TraceContext trace) throws PublishFailed {
        var record = new ProducerRecord<String, byte[]>(topic, command.accountId(), JsonCodec.toBytes(command.payload()));

        var traceParent = TraceParent.parse(trace.traceparent()).orElseGet(TraceParent::generate);
        KafkaTracePropagation.inject(record.headers(), traceParent);
        record.headers().add(StandardHeaders.RECEIVED_AT, utf8(clock.instant().toString()));
        record.headers().add(StandardHeaders.SCHEMA_VERSION, utf8(SCHEMA_VERSION));
        record.headers().add(StandardHeaders.COMMAND_TYPE, utf8(COMMAND_TYPE));

        try {
            producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailed("publicação interrompida", e);
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            throw new PublishFailed("Kafka não confirmou a gravação em " + timeout.toMillis() + " ms", e);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
