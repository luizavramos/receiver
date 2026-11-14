package br.com.motorfraude.receiver.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.motorfraude.contracts.Channel;
import br.com.motorfraude.contracts.Counterparty;
import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.Money;
import br.com.motorfraude.contracts.TransactionType;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T103 — invariantes de domínio que o JSON Schema não cobre ou que reforçam o contrato (spec 001, FR-002). */
class TransactionCommandTest {

    private static final Instant NOW = Instant.parse("2026-11-13T21:30:00Z");

    private static EvaluateTransaction payload(long value, String currency, Instant occurredAt) {
        return new EvaluateTransaction(
                1,
                "tx_20261113_0001",
                "acc_tok_00000001",
                "cus_tok_00000001",
                TransactionType.PIX,
                new Money(value, currency),
                new Counterparty("acc_tok_00004711", "60701190"),
                Channel.MOBILE,
                "dev_0f3a9c1d5b7e2a64",
                occurredAt);
    }

    @Test
    @DisplayName("transação válida vira comando e expõe transactionId e accountId (chave Kafka)")
    void valida() {
        var command = TransactionCommand.of(payload(15_000, "BRL", NOW.minusSeconds(30)), NOW);

        assertThat(command.transactionId()).isEqualTo("tx_20261113_0001");
        assertThat(command.accountId()).isEqualTo("acc_tok_00000001");
        assertThat(command.payload().amount().value()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("valor zero é rejeitado")
    void valorZero() {
        assertThatThrownBy(() -> TransactionCommand.of(payload(0, "BRL", NOW), NOW))
                .isInstanceOf(InvalidTransactionException.class)
                .satisfies(e -> assertThat(((InvalidTransactionException) e).violations())
                        .containsExactly("amount.value: deve ser maior que zero"));
    }

    @Test
    @DisplayName("valor negativo é rejeitado")
    void valorNegativo() {
        assertThatThrownBy(() -> TransactionCommand.of(payload(-1, "BRL", NOW), NOW))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    @DisplayName("moeda diferente de BRL é rejeitada")
    void moeda() {
        assertThatThrownBy(() -> TransactionCommand.of(payload(100, "USD", NOW), NOW))
                .isInstanceOf(InvalidTransactionException.class)
                .satisfies(e -> assertThat(((InvalidTransactionException) e).violations())
                        .containsExactly("amount.currency: somente BRL é aceito"));
    }

    @Test
    @DisplayName("occurredAt até 5 min no futuro é aceito (limite inclusive)")
    void futuroNoLimite() {
        var command = TransactionCommand.of(payload(100, "BRL", NOW.plus(Duration.ofMinutes(5))), NOW);

        assertThat(command).isNotNull();
    }

    @Test
    @DisplayName("occurredAt além de 5 min no futuro é rejeitado")
    void futuroAlemDoLimite() {
        var tooFar = NOW.plus(Duration.ofMinutes(5)).plusMillis(1);

        assertThatThrownBy(() -> TransactionCommand.of(payload(100, "BRL", tooFar), NOW))
                .isInstanceOf(InvalidTransactionException.class)
                .satisfies(e -> assertThat(((InvalidTransactionException) e).violations())
                        .containsExactly("occurredAt: não pode estar mais de 5 minutos no futuro"));
    }

    @Test
    @DisplayName("occurredAt no passado distante é aceito (retries e atrasos são legítimos)")
    void passado() {
        assertThat(TransactionCommand.of(payload(100, "BRL", NOW.minus(Duration.ofDays(30))), NOW)).isNotNull();
    }

    @Test
    @DisplayName("várias violações voltam juntas, para o cliente corrigir de uma vez")
    void variasViolacoes() {
        var tooFar = NOW.plus(Duration.ofHours(1));

        assertThatThrownBy(() -> TransactionCommand.of(payload(0, "USD", tooFar), NOW))
                .isInstanceOf(InvalidTransactionException.class)
                .satisfies(e -> assertThat(((InvalidTransactionException) e).violations()).hasSize(3));
    }

    @Test
    @DisplayName("payload ou relógio nulos são erro de programação, não violação de negócio")
    void nulos() {
        assertThatThrownBy(() -> TransactionCommand.of(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionCommand.of(payload(1, "BRL", NOW), null))
                .isInstanceOf(NullPointerException.class);
    }
}
