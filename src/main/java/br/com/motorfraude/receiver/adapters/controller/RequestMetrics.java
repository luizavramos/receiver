package br.com.motorfraude.receiver.adapters.controller;

import br.com.motorfraude.receiver.adapters.presenter.Outcome;

/** Porta de métricas do adapter REST: o controller reporta; quem mede (Micrometer) vive na infraestrutura. */
public interface RequestMetrics {

    void record(Outcome outcome, String client);
}
