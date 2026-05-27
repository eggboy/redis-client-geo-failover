package io.jaylee.amr.demo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.mcf.ConnectionFailoverException;
import redis.clients.jedis.mcf.JedisFailoverException;

import java.time.Instant;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(NoSuchElementException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage()));
	}

	@ExceptionHandler({ JedisFailoverException.class, ConnectionFailoverException.class,
			JedisConnectionException.class })
	public ResponseEntity<ErrorResponse> handleRedisUnavailable(Exception ex) {
		log.warn("Redis call failed: {}", ex.toString());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Unexpected error", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		if (status == null) {
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}
		String message = (ex instanceof ErrorResponseException ere && ere.getBody() != null
				&& ere.getBody().getDetail() != null) ? ere.getBody().getDetail() : ex.getMessage();
		if (message == null || message.isBlank()) {
			message = status.getReasonPhrase();
		}
		return ResponseEntity.status(status).headers(headers).body(ErrorResponse.of(status, message));
	}

	public record ErrorResponse(String errorCode, String message, Instant timestamp) {
		public static ErrorResponse of(HttpStatus status, String message) {
			return new ErrorResponse(status.name(), message, Instant.now());
		}
	}

}
