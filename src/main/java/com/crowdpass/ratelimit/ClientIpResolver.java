package com.crowdpass.ratelimit;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client address used for per-IP limits: the direct TCP peer. Forwarded headers are
 * deliberately ignored ({@code server.forward-headers-strategy: none}) because they are
 * client-controlled until trusted proxies are configured for the AWS deployment (Phase 10).
 */
@Component
public class ClientIpResolver {

	public String resolve(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

}
