package com.crowdpass.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.crowdpass.common.SecretKeys;

/**
 * Converts IP addresses and emails into opaque identities for Redis keys:
 * HMAC-SHA256 with {@code CROWDPASS_RATE_LIMIT_SECRET}, truncated to the first 128 bits and
 * encoded as 32 lowercase hex characters. Raw IPs and emails never reach Redis.
 */
@Component
class RateLimitIdentities {

	static final int IDENTITY_BYTES = 16;
	private static final int MIN_SECRET_BYTES = 32;
	private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

	private final SecretKeySpec key;

	RateLimitIdentities(RateLimitProperties properties) {
		this.key = new SecretKeySpec(SecretKeys.resolve("crowdpass.rate-limit.secret", "CROWDPASS_RATE_LIMIT_SECRET",
				properties.secret(), properties.ephemeralSecretAllowed(), MIN_SECRET_BYTES), "HmacSHA256");
	}

	/** IPv4 by address; IPv6 by its /64 prefix, since one host can rotate addresses within it. */
	String clientIp(String remoteAddress) {
		return hmac("ip:" + normalizeIp(remoteAddress));
	}

	/** {@code canonicalEmail} must already be trimmed and lowercased. */
	String email(String canonicalEmail) {
		return hmac("email:" + canonicalEmail);
	}

	static String normalizeIp(String remoteAddress) {
		String literal = remoteAddress.strip();
		int scope = literal.indexOf('%');
		if (scope >= 0) {
			literal = literal.substring(0, scope);
		}
		if (!IP_LITERAL.matcher(literal).matches()) {
			// Not an IP literal; never resolve it through DNS.
			return "other:" + literal;
		}
		try {
			InetAddress address = InetAddress.getByName(literal);
			if (address instanceof Inet6Address) {
				byte[] prefix = Arrays.copyOf(address.getAddress(), 16);
				Arrays.fill(prefix, 8, 16, (byte) 0);
				return "v6:" + InetAddress.getByAddress(prefix).getHostAddress() + "/64";
			}
			return "v4:" + address.getHostAddress();
		}
		catch (UnknownHostException ex) {
			return "other:" + literal;
		}
	}

	private String hmac(String input) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, IDENTITY_BYTES);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 unavailable", ex);
		}
	}

}
