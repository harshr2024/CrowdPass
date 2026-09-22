package com.crowdpass.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret base64-encoded HMAC key of at least 256 bits
 * @param ephemeralSecretAllowed generate a random key when {@code secret} is absent; enabled only
 *     for local development and tests so production fails fast instead
 */
@ConfigurationProperties("crowdpass.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl, boolean ephemeralSecretAllowed) {
}
