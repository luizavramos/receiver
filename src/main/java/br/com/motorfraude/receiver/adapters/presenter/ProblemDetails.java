package br.com.motorfraude.receiver.adapters.presenter;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Monta as respostas de erro no formato Problem Details (RFC 9457). O corpo é um mapa feito à mão, para não depender
 * da serialização do {@code ProblemDetail} do Spring com o Jackson 3.
 */
final class ProblemDetails {

    private ProblemDetails() {}

    static Reply of(Outcome outcome, HttpStatus status, String title, String detail, List<String> errors) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        if (!errors.isEmpty()) {
            body.put("errors", errors);
        }
        return new Reply(outcome, ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body));
    }

    static Reply retryLater(Outcome outcome, HttpStatus status, String title, String detail) {
        var body = of(outcome, status, title, detail, List.of()).response().getBody();
        return new Reply(outcome, ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, "1").body(body));
    }
}
