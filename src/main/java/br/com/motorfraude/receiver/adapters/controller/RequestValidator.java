package br.com.motorfraude.receiver.adapters.controller;

import br.com.motorfraude.contracts.Schemas;
import java.util.List;
import tools.jackson.databind.JsonNode;

public class RequestValidator {

    static final String SCHEMA = "evaluate-transaction.v1";

    public List<String> validate(JsonNode body) {
        return Schemas.validate(SCHEMA, body).errors();
    }
}
