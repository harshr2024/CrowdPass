package com.crowdpass.auth;

/** @param expiresIn access-token lifetime in seconds */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
}
