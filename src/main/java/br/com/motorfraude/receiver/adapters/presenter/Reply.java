package br.com.motorfraude.receiver.adapters.presenter;

import java.util.Map;
import org.springframework.http.ResponseEntity;

/** A resposta HTTP junto com o desfecho que a originou: o controller devolve a resposta e reporta o desfecho à métrica. */
public record Reply(Outcome outcome, ResponseEntity<Map<String, Object>> response) {}
