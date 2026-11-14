package br.com.motorfraude.receiver.application.port.out;

/**
 * Escudo de carga contra retries óbvios (ADR-10). Não garante processamento único: quem garante é o motor.
 *
 * <p>Duas fases: {@link #tryReserve} grava PENDING com TTL curto; depois do ack do Kafka, {@link #confirm} grava DONE
 * com TTL longo. Só DONE significa "já aceito".
 *
 * <p>Nenhuma operação lança exceção: falha de infraestrutura vira {@link Reservation#UNAVAILABLE} (fail-open) ou é
 * engolida (melhor esforço) no adapter.
 */
public interface DuplicateGuard {

    enum Reservation {
        /** Esta requisição ganhou a reserva (PENDING gravado). */
        RESERVED,
        /** Já aceito e confirmado antes (DONE). */
        ALREADY_SEEN,
        /** Outra requisição está publicando agora (PENDING). */
        IN_FLIGHT,
        /** Redis fora do ar ou lento: segue sem escudo (fail-open). */
        UNAVAILABLE
    }

    Reservation tryReserve(String transactionId);

    /** Publicação confirmada: PENDING para DONE. Melhor esforço. */
    void confirm(String transactionId);

    /** Publicação falhou: apaga a reserva para o retry não ser barrado. Melhor esforço. */
    void release(String transactionId);
}
