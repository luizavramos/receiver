package br.com.motorfraude.receiver.adapters.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.receiver.adapters.controller.TransactionRequestParser.Parsed;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionRequestParserTest {

    private static final Instant NOW = Instant.parse("2026-11-13T21:30:00Z");
    private static final String TX_ID = "tx_20261113_0001";

    private final TransactionRequestParser parser = new TransactionRequestParser(new RequestValidator(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static String body(String amountValue, String occurredAt) {
        return """
                {
                  "schemaVersion": 1,
                  "transactionId": "tx_20261113_0001",
                  "accountId": "acc_tok_00000001",
                  "customerId": "cus_tok_00000001",
                  "type": "PIX",
                  "amount": { "value": %s, "currency": "BRL" },
                  "counterparty": { "accountToken": "acc_tok_00004711", "bankIspb": "60701190" },
                  "channel": "MOBILE",
                  "deviceIdHash": "dev_0f3a9c1d5b7e2a64",
                  "occurredAt": "%s"
                }
                """.formatted(amountValue, occurredAt);
    }

    private Parsed parse(String body, String idempotencyKey) {
        return parser.parse(body.getBytes(StandardCharsets.UTF_8), idempotencyKey);
    }

    @Test
    @DisplayName("corpo válido com a chave igual ao transactionId vira o comando de domínio")
    void valido() {
        var result = parse(body("15000", "2026-11-13T21:29:30Z"), TX_ID);

        assertThat(result).isInstanceOfSatisfying(Parsed.Valid.class, valid -> {
            assertThat(valid.command().transactionId()).isEqualTo(TX_ID);
            assertThat(valid.command().accountId()).isEqualTo("acc_tok_00000001");
        });
    }

    @Test
    @DisplayName("JSON inválido: erro de corpo, junto com a chave ausente se for o caso")
    void jsonInvalido() {
        var result = parser.parse("{ nao e json".getBytes(StandardCharsets.UTF_8), null);

        assertThat(result).isInstanceOfSatisfying(Parsed.Invalid.class, invalid -> assertThat(invalid.errors())
                .containsExactly("Idempotency-Key: header obrigatório", "corpo: não é um JSON válido"));
    }

    @Test
    @DisplayName("Idempotency-Key ausente, em branco ou diferente do transactionId")
    void idempotencyKey() {
        var ok = body("15000", "2026-11-13T21:29:30Z");

        assertThat(parse(ok, null)).isInstanceOfSatisfying(Parsed.Invalid.class,
                i -> assertThat(i.errors()).containsExactly("Idempotency-Key: header obrigatório"));
        assertThat(parse(ok, "  ")).isInstanceOfSatisfying(Parsed.Invalid.class,
                i -> assertThat(i.errors()).contains("Idempotency-Key: header obrigatório"));
        assertThat(parse(ok, "tx_outra")).isInstanceOfSatisfying(Parsed.Invalid.class,
                i -> assertThat(i.errors()).containsExactly("Idempotency-Key: deve ser igual ao transactionId do corpo"));
    }

    @Test
    @DisplayName("violação do contrato (valor decimal) é recusada com a mensagem do schema")
    void contrato() {
        var result = parse(body("150.5", "2026-11-13T21:29:30Z"), TX_ID);

        assertThat(result).isInstanceOfSatisfying(Parsed.Invalid.class, invalid -> assertThat(invalid.errors()).containsExactly("number encontrado, integer esperado"));
    }

    @Test
    @DisplayName("invariante de domínio (ocorrência mais de 5 minutos no futuro) vem depois do contrato")
    void invariantes() {
        var result = parse(body("15000", "2026-11-14T21:29:30Z"), TX_ID);

        assertThat(result).isInstanceOfSatisfying(Parsed.Invalid.class, invalid -> assertThat(invalid.errors())
                .containsExactly("occurredAt: não pode estar mais de 5 minutos no futuro"));
    }

    @Test
    @DisplayName("valor zero é barrado pelo schema, antes das invariantes de domínio")
    void valorZero() {
        var result = parse(body("0", "2026-11-13T21:29:30Z"), TX_ID);

        assertThat(result).isInstanceOfSatisfying(Parsed.Invalid.class, invalid -> assertThat(invalid.errors()).containsExactly("deve ter um valor mínimo de 1"));
    }

    @Test
    @DisplayName("corpo vazio não é um JSON de transação")
    void vazio() {
        assertThat(parser.parse(new byte[0], TX_ID)).isInstanceOf(Parsed.Invalid.class);
    }
}
