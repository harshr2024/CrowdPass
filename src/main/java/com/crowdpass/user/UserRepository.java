package com.crowdpass.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** {@code email} must already be canonical (see {@link User#canonicalEmail(String)}). */
	Optional<User> findByEmail(String email);

}
