package br.com.motorfraude.receiver.adapters.controller;

import java.util.Set;

public final class AllowedClients {

    private final Set<String> ids;

    public AllowedClients(Set<String> ids) {
        this.ids = Set.copyOf(ids);
    }

    public boolean contains(String clientId) {
        return clientId != null && ids.contains(clientId);
    }
}
