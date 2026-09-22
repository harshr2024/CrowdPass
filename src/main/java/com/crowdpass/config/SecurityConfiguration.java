package com.crowdpass.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.crowdpass.exception.SecurityErrorHandler;

/**
 * Stateless bearer-token security. All access rules live here; anything not listed requires
 * authentication. Role rules express capability only: ownership is enforced in services.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler securityErrorHandler)
			throws Exception {
		http
				// No cookies or sessions: browsers never attach the Authorization header automatically,
				// so there is no ambient credential for CSRF to abuse. Re-enable if tokens move to cookies.
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/events", "/api/events/*").permitAll()
						.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
						.permitAll()
						.requestMatchers("/error").permitAll()
						.requestMatchers("/api/organizer/**").hasRole("ORGANIZER")
						.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
						.authenticationEntryPoint(securityErrorHandler)
						.accessDeniedHandler(securityErrorHandler))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(securityErrorHandler)
						.accessDeniedHandler(securityErrorHandler));
		return http.build();
	}

	/** Roles are additive capabilities. */
	@Bean
	RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.withDefaultRolePrefix()
				.role("ADMIN").implies("ORGANIZER")
				.role("ORGANIZER").implies("USER")
				.build();
	}

	/** Stores {@code {bcrypt}...} so the algorithm can change later without invalidating hashes. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
	}

	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("role");
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

}
