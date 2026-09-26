import http from 'k6/http';
import exec from 'k6/execution';
import { arrivalRate, authHeaders, baseUrl, fixture, thresholds, tokenFor } from './lib/config.js';
import { classify, successfulMutationLatency } from './lib/metrics.js';

export const options = {
  scenarios: { mixed: arrivalRate(100, '60s') }, thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const index = exec.scenario.iterationInTest;
  const bucket = index % 100;
  let response;
  if (bucket < 70) {
    response = http.get(`${baseUrl}/api/events?size=20`, { tags: { endpoint: 'mixed-event-list' } });
  } else if (bucket < 85) {
    response = http.get(`${baseUrl}/api/events/${fixture.eventId}`, { tags: { endpoint: 'mixed-event-detail' } });
  } else if (bucket < 95) {
    response = http.get(`${baseUrl}/api/notifications`, {
      headers: authHeaders(tokenFor(index)), tags: { endpoint: 'mixed-notifications' },
    });
  } else {
    const reservationIndex = Math.floor(index / 100) * 5 + (bucket - 95);
    response = http.post(`${baseUrl}/api/events/${fixture.eventId}/reservations`, '{}', {
      headers: authHeaders(tokenFor(reservationIndex)), tags: { endpoint: 'mixed-reservation' },
    });
  }
  if (classify(response, bucket < 95 ? [200] : [201]) === 'success' && bucket >= 95) {
    successfulMutationLatency.add(response.timings.duration);
  }
}
