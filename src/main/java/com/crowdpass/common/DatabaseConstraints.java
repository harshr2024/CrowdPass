package com.crowdpass.common;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Identifies which named database constraint caused a failure, so services can map specific
 * violations to domain errors. Constraint names are internal and never returned to clients.
 */
public final class DatabaseConstraints {

	private DatabaseConstraints() {
	}

	public static Optional<String> violatedConstraint(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return Optional.ofNullable(violation.getConstraintName());
			}
		}
		return Optional.empty();
	}

}
