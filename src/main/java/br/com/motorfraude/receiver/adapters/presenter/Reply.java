package br.com.motorfraude.receiver.adapters.presenter;

import java.util.Map;
import org.springframework.http.ResponseEntity;

public record Reply(Outcome outcome, ResponseEntity<Map<String, Object>> response) {}
