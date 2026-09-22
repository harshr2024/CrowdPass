package com.crowdpass.auth;

import org.springframework.http.HttpStatus;

import com.crowdpass.exception.ApiException;

public class EmailAlreadyRegisteredException extends ApiException {

	public EmailAlreadyRegisteredException() {
		super(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists.");
	}

}
