package com.crowdpass.event;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResponse<T>(
		List<T> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
