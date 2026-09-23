package com.crowdpass.auth;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crowdpass.ratelimit.RateLimitPolicy;
import com.crowdpass.ratelimit.RateLimiter;
import com.crowdpass.user.User;
import com.crowdpass.user.UserResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Rate limits are checked after request validation (malformed bodies cost nothing) and before any
 * BCrypt work. They only throttle email enumeration through {@code EMAIL_ALREADY_REGISTERED}; the
 * information difference itself remains until an email-verification flow exists.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;
	private final RateLimiter rateLimiter;

	public AuthController(AuthService authService, RateLimiter rateLimiter) {
		this.authService = authService;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/register")
	public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request,
			HttpServletRequest httpRequest) {
		rateLimiter.checkClientIp(RateLimitPolicy.REGISTER_IP, httpRequest);
		return ResponseEntity.created(URI.create("/api/users/me")).body(authService.register(request));
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
		rateLimiter.checkClientIp(RateLimitPolicy.LOGIN_IP, httpRequest);
		rateLimiter.checkAccount(RateLimitPolicy.LOGIN_ACCOUNT, User.canonicalEmail(request.email()));
		return authService.login(request);
	}

}
