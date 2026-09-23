package com.crowdpass.common;

import java.util.List;

import org.springframework.data.domain.Page;

/** The API's pagination envelope; {@code page} is 0-based. */
public record PageResponse<T>(
		List<T> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
