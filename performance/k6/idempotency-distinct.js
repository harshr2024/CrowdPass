import http from 'k6/http';
import exec from 'k6/execution';
import { arrivalRate, authHeaders, baseUrl, fixture, thresholds, tokenFor } from './lib/config.js';
import { classify, successfulMutationLatency } from './lib/metrics.js';

export const options = {
  scenarios: { distinct_keys: arrivalRate(50, '10s') }, thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const index = exec.scenario.iterationInTest;
  const response = http.post(`${baseUrl}/api/events/${fixture.eventId}/reservations`, '{}', {
    headers: authHeaders(tokenFor(index), `perf-distinct-key-${index}`),
    tags: { endpoint: 'idempotency-distinct' },
  });
  if (classify(response, [201]) === 'success') successfulMutationLatency.add(response.timings.duration);
}
