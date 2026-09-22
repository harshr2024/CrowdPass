package com.crowdpass.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.crowdpass.user.Role;

/** Issues access tokens. Claims carry identity and role only, never profile data. */
@Service
public class JwtTokenService {

	private final JwtEncoder jwtEncoder;
	private final JwtProperties properties;
	private final Clock clock;

	public JwtTokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
		this.clock = clock;
	}

	public String issueAccessToken(UUID userId, Role role) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.issuer())
				.subject(userId.toString())
				.claim(JwtConfiguration.ROLE_CLAIM, role.name())
				.issuedAt(now)
				.expiresAt(now.plus(properties.accessTokenTtl()))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public Duration accessTokenTtl() {
		return properties.accessTokenTtl();
	}

}
