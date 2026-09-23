package com.crowdpass.idempotency;

import tools.jackson.databind.JsonNode;

public record IdempotentResponse(int status, JsonNode body, String location, boolean replay) {
}
