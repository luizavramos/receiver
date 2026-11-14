package br.com.motorfraude.receiver.adapters.in.rest;

import br.com.motorfraude.contracts.Schemas;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Valida o corpo contra o JSON Schema {@code evaluate-transaction.v1}: o contrato estrito do módulo contracts. */
public class RequestValidator {

    static final String SCHEMA = "evaluate-transaction.v1";

    /** @return as violações, uma por campo; vazio se o corpo respeita o contrato */
    public List<String> validate(JsonNode body) {
        return Schemas.validate(SCHEMA, body).errors();
    }
}
