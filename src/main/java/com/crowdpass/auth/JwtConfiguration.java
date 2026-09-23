package com.crowdpass.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.crowdpass.common.SecretKeys;
import com.crowdpass.user.Role;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

	static final String ROLE_CLAIM = "role";

	private static final int MIN_SECRET_BYTES = 32;
	private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

	@Bean
	SecretKey jwtSigningKey(JwtProperties properties) {
		return resolveSigningKey(properties.secret(), properties.ephemeralSecretAllowed());
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
	}

	/** Accepts only HS256 tokens from our issuer with a UUID subject, a known role, and an expiry. */
	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
		timestamps.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				timestamps,
				new JwtIssuerValidator(properties.issuer()),
				new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
				new JwtClaimValidator<String>(JwtClaimNames.SUB, JwtConfiguration::isUuid),
				new JwtClaimValidator<String>(ROLE_CLAIM, JwtConfiguration::isKnownRole)));
		return decoder;
	}

	static SecretKey resolveSigningKey(String base64Secret, boolean ephemeralSecretAllowed) {
		byte[] key = SecretKeys.resolve("crowdpass.jwt.secret", "CROWDPASS_JWT_SECRET", base64Secret,
				ephemeralSecretAllowed, MIN_SECRET_BYTES);
		return new SecretKeySpec(key, "HmacSHA256");
	}

	private static boolean isUuid(String value) {
		if (value == null) {
			return false;
		}
		try {
			return UUID.fromString(value).toString().equals(value);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean isKnownRole(String value) {
		for (Role role : Role.values()) {
			if (role.name().equals(value)) {
				return true;
			}
		}
		return false;
	}

}
