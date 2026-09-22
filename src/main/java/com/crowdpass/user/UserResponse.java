package com.crowdpass.user;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName, Role role, Instant createdAt) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
				user.getCreatedAt());
	}

}
