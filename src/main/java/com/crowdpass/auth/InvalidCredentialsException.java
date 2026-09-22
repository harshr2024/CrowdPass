package com.crowdpass.auth;

import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

/** Used for both unknown email and wrong password so responses do not reveal which accounts exist. */
public class InvalidCredentialsException extends ApiException {

	public InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
	}

}
