package io.jaylee.amr.demo.controller;

import io.jaylee.amr.demo.domain.Session;
import io.jaylee.amr.demo.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/sessions")
public class SessionController {

	private final SessionService service;

	public SessionController(SessionService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<SessionResponse> create(@RequestBody(required = false) CreateSessionRequest request) {
		Session created = service.create(request == null ? null : request.userId(),
				request == null ? null : request.attributes());
		SessionResponse body = SessionResponse.from(created);
		URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
			.path("/{sid}")
			.buildAndExpand(body.sessionId())
			.toUri();
		return ResponseEntity.created(location).body(body);
	}

	@GetMapping("/{sessionId}")
	public SessionResponse get(@PathVariable String sessionId) {
		return service.get(sessionId)
			.map(SessionResponse::from)
			.orElseThrow(() -> new NoSuchElementException("Session '" + sessionId + "' not found"));
	}

	@PatchMapping("/{sessionId}")
	public SessionResponse patch(@PathVariable String sessionId,
			@RequestBody(required = false) PatchSessionRequest request) {
		return service.patch(sessionId, request == null ? null : request.attributes())
			.map(SessionResponse::from)
			.orElseThrow(() -> new NoSuchElementException("Session '" + sessionId + "' not found"));
	}

	@DeleteMapping("/{sessionId}")
	public ResponseEntity<Void> delete(@PathVariable String sessionId) {
		service.delete(sessionId);
		return ResponseEntity.noContent().build();
	}

	public record CreateSessionRequest(String userId, Map<String, String> attributes) {
	}

	public record PatchSessionRequest(Map<String, String> attributes) {
	}

}
