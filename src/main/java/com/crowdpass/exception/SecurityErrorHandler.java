package com.crowdpass.exception;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401 and 403 responses from the security filter chain, which runs before Spring MVC and
 * is therefore not covered by {@link GlobalExceptionHandler}. The reason a token was rejected is
 * deliberately not disclosed.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ApiErrors apiErrors;
	private final JsonMapper jsonMapper;

	public SecurityErrorHandler(ApiErrors apiErrors, JsonMapper jsonMapper) {
		this.apiErrors = apiErrors;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		write(response, request, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, request, HttpStatus.FORBIDDEN, "FORBIDDEN",
				"You do not have permission to perform this action.");
	}

	private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String code,
			String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(),
				apiErrors.create(status, code, message, request.getRequestURI()));
	}

}
