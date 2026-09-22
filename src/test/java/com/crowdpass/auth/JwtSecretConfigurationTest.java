package com.crowdpass.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import javax.crypto.SecretKey;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.crowdpass.config.ClockConfiguration;

/**
 * Starts only the JWT configuration (no web server or database) to prove startup behavior for
 * each secret configuration. application.yml is not loaded, so nothing enables ephemeral keys
 * unless a test sets it.
 */
class JwtSecretConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(JwtConfiguration.class, ClockConfiguration.class)
			.withPropertyValues("crowdpass.jwt.issuer=crowdpass-api", "crowdpass.jwt.access-token-ttl=60m");

	@ParameterizedTest(name = "secret=''{0}''")
	@CsvSource(value = {
			"<none>,       must be set",
			"'',           must be set",
			"not base64!,  not valid base64",
			"c2hvcnQ=,     at least 32 bytes" }, nullValues = "<none>")
	void failsStartupWithoutValidSecret(String secret, String expectedMessage) {
		ApplicationContextRunner runner = secret == null ? contextRunner
				: contextRunner.withPropertyValues("crowdpass.jwt.secret=" + secret);

		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedMessage);
		});
	}

	@Test
	void startsWithValid256BitSecret() {
		String secret = Base64.getEncoder().encodeToString(new byte[32]);

		contextRunner.withPropertyValues("crowdpass.jwt.secret=" + secret).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(SecretKey.class).getEncoded()).hasSize(32);
		});
	}

	@Test
	void generatesEphemeralKeyOnlyWhenExplicitlyAllowed() {
		contextRunner.withPropertyValues("crowdpass.jwt.ephemeral-secret-allowed=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(SecretKey.class).getEncoded()).hasSize(32);
		});
	}

}
