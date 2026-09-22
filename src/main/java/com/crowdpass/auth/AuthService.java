package com.crowdpass.auth;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crowdpass.user.Role;
import com.crowdpass.user.User;
import com.crowdpass.user.UserRepository;
import com.crowdpass.user.UserResponse;

@Service
public class AuthService {

	private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenService jwtTokenService;
	private final Clock clock;

	/** Verified against when the email is unknown, so both failure paths cost one BCrypt check. */
	private final String dummyPasswordHash;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
			JwtTokenService jwtTokenService, Clock clock) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenService = jwtTokenService;
		this.clock = clock;
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * Always creates a USER. Uniqueness is enforced by the database constraint rather than a
	 * pre-check, which also covers two simultaneous registrations for the same email.
	 */
	@Transactional
	public UserResponse register(RegisterRequest request) {
		User user = new User(request.email(), passwordEncoder.encode(request.password()), request.displayName(),
				Role.USER, clock.instant());
		try {
			userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			if (violates(ex, EMAIL_UNIQUE_CONSTRAINT)) {
				throw new EmailAlreadyRegisteredException();
			}
			throw ex;
		}
		return UserResponse.from(user);
	}

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		Optional<User> user = userRepository.findByEmail(User.canonicalEmail(request.email()));
		String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
		boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
		// BCrypt's matches() truncates input beyond 72 bytes, so over-long input must be rejected
		// explicitly or a correct password followed by extra characters would authenticate.
		if (user.isEmpty() || !passwordMatches || PasswordPolicy.exceedsMaxBytes(request.password())) {
			throw new InvalidCredentialsException();
		}
		String token = jwtTokenService.issueAccessToken(user.get().getId(), user.get().getRole());
		return new LoginResponse(token, "Bearer", jwtTokenService.accessTokenTtl().toSeconds());
	}

	private static boolean violates(Throwable ex, String constraintName) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return constraintName.equals(violation.getConstraintName());
			}
		}
		return false;
	}

}
