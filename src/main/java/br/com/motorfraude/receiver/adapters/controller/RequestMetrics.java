package br.com.motorfraude.receiver.adapters.controller;

import br.com.motorfraude.receiver.adapters.presenter.Outcome;

public interface RequestMetrics {

    void record(Outcome outcome, String client);
}
