package com.crowdpass.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public registration. There is intentionally no role property: every registration creates a
 * USER, and unknown JSON fields are rejected.
 */
public record RegisterRequest(
		@NotBlank(message = "must not be blank")
		@Size(max = 254, message = "must be at most 254 characters")
		@Pattern(regexp = EMAIL_PATTERN, message = "must be a valid email address")
		String email,

		@NotNull(message = "must not be null")
		@Size(min = PasswordPolicy.MIN_LENGTH, message = "must be at least {min} characters")
		@MaxUtf8Bytes(PasswordPolicy.MAX_UTF8_BYTES)
		String password,

		@NotBlank(message = "must not be blank")
		@Size(max = 100, message = "must be at most 100 characters")
		String displayName) {

	/**
	 * Printable ASCII without spaces, one {@code @}, and a dot in the domain. ASCII-only keeps Java
	 * and PostgreSQL lowercasing identical; internationalized addresses are not supported.
	 */
	static final String EMAIL_PATTERN = "[\\x21-\\x7E&&[^@]]+@[\\x21-\\x7E&&[^@]]+\\.[\\x21-\\x7E&&[^@]]+";

	public RegisterRequest {
		email = email == null ? null : email.strip();
		displayName = displayName == null ? null : displayName.strip();
	}

	@Override
	public String toString() {
		return "RegisterRequest[email=" + email + ", password=<redacted>, displayName=" + displayName + "]";
	}

}
