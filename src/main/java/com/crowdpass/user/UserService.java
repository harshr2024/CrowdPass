package com.crowdpass.user;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository userRepository;

	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional(readOnly = true)
	public UserResponse getUser(UUID userId) {
		return userRepository.findById(userId)
				.map(UserResponse::from)
				.orElseThrow(AuthenticatedUserNotFoundException::new);
	}

}
