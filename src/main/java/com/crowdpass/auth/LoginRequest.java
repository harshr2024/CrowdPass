package com.crowdpass.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Only presence is validated; format problems are reported as invalid credentials. */
public record LoginRequest(
		@NotBlank(message = "must not be blank") String email,
		@NotNull(message = "must not be null") String password) {

	@Override
	public String toString() {
		return "LoginRequest[email=" + email + ", password=<redacted>]";
	}

}
