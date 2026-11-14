package br.com.motorfraude.receiver.adapters.in.rest;

import java.util.Set;

/**
 * Origens autorizadas a chamar o receiver (FR-007a). Localmente identificadas por {@code X-Client-Id}; em produção, pelo
 * certificado mTLS. A lista fechada também limita a cardinalidade dos labels de métrica.
 */
public final class AllowedClients {

    private final Set<String> ids;

    public AllowedClients(Set<String> ids) {
        this.ids = Set.copyOf(ids);
    }

    public boolean contains(String clientId) {
        return clientId != null && ids.contains(clientId);
    }
}
