import http from 'k6/http';
import exec from 'k6/execution';
import { arrivalRate, authHeaders, baseUrl, fixture, thresholds, tokenFor } from './lib/config.js';
import { classify, successfulMutationLatency } from './lib/metrics.js';

export const options = {
  scenarios: { hot_event: arrivalRate(100, '5s') }, thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const index = exec.scenario.iterationInTest;
  const response = http.post(`${baseUrl}/api/events/${fixture.eventId}/reservations`, '{}', {
    headers: authHeaders(tokenFor(index)),
    tags: { endpoint: 'hot-event-reservation' },
    responseCallback: http.expectedStatuses(201, 409),
  });
  const outcome = classify(response, [201], ['EVENT_FULL']);
  if (outcome === 'success') successfulMutationLatency.add(response.timings.duration);
}
