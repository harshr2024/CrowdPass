package com.crowdpass.event;

import java.util.List;

import org.springframework.data.domain.Page;

public record EventPageResponse(
		List<EventResponse> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	static EventPageResponse from(Page<EventResponse> page) {
		return new EventPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
