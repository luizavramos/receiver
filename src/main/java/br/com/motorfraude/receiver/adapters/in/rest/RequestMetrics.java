package br.com.motorfraude.receiver.adapters.in.rest;

/** Porta de métricas do adapter REST: o controller reporta; quem mede (Micrometer) vive na infraestrutura. */
public interface RequestMetrics {

    /**
     * @param client a origem autorizada, ou {@link TransactionController#UNKNOWN_CLIENT} para qualquer outra (limita a
     *     cardinalidade do label)
     */
    void record(Outcome outcome, String client);
}
