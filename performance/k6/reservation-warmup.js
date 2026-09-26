import http from 'k6/http';
import exec from 'k6/execution';
import { arrivalRate, authHeaders, baseUrl, fixture, tokenFor } from './lib/config.js';

export const options = {
  scenarios: { reservation_warmup: arrivalRate(100, '10s') },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  http.post(`${baseUrl}/api/events/${fixture.warmupEventId}/reservations`, '{}', {
    headers: authHeaders(tokenFor(exec.scenario.iterationInTest)),
    tags: { endpoint: 'reservation-warmup' },
  });
}
