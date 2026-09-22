package com.crowdpass.exception;

import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.crowdpass.common.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Converts every exception raised by MVC handling into an {@link ApiError}. Messages returned to
 * clients are written here or by {@link ApiException} subclasses; exception messages from
 * frameworks, SQL, or Hibernate are never passed through.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

	private final Clock clock;

	public GlobalExceptionHandler(Clock clock) {
		this.clock = clock;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
		return error(ex.getStatus(), ex.getCode(), ex.getMessage(), request.getRequestURI());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.",
				request.getRequestURI());
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String message = ex.getParameterValidationResults().stream()
				.map(GlobalExceptionHandler::describe)
				.collect(Collectors.joining("; "));
		return withHeaders(error(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, message, path(request)), headers);
	}

	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
				: ex.getPropertyName();
		return withHeaders(error(HttpStatus.BAD_REQUEST, VALIDATION_FAILED,
				"Parameter '" + name + "' has an invalid value.", path(request)), headers);
	}

	/** Fallback for the remaining Spring MVC exceptions (404 route, 405, 415, and so on). */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpStatus status = HttpStatus.valueOf(statusCode.value());
		if (status.is5xxServerError()) {
			log.error("Request failed with {}", status.value(), ex);
		}
		return withHeaders(error(status, status.name(), status.getReasonPhrase() + ".", path(request)), headers);
	}

	private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, String path) {
		ApiError body = new ApiError(Instant.now(clock), status.value(), status.getReasonPhrase(), code, message,
				path, MDC.get(RequestIdFilter.MDC_KEY));
		return ResponseEntity.status(status).body(body);
	}

	private static ResponseEntity<Object> withHeaders(ResponseEntity<ApiError> response, HttpHeaders headers) {
		return ResponseEntity.status(response.getStatusCode()).headers(headers).body(response.getBody());
	}

	private static String path(WebRequest request) {
		return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
	}

	private static String describe(ParameterValidationResult result) {
		String reasons = result.getResolvableErrors().stream()
				.map(error -> error.getDefaultMessage())
				.collect(Collectors.joining(", "));
		return "Parameter '" + parameterName(result.getMethodParameter()) + "' " + reasons + ".";
	}

	private static String parameterName(MethodParameter parameter) {
		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		if (requestParam != null && !requestParam.name().isEmpty()) {
			return requestParam.name();
		}
		PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
		if (pathVariable != null && !pathVariable.name().isEmpty()) {
			return pathVariable.name();
		}
		return parameter.getParameterName();
	}

}
