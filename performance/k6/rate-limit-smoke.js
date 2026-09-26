import http from 'k6/http';
import { authHeaders, baseUrl, fixture, tokenFor } from './lib/config.js';
import { Counter } from 'k6/metrics';

const limited = new Counter('rate_limited_responses');
const unexpected = new Counter('unexpected_responses');

export const options = {
  scenarios: { abuse: { executor: 'shared-iterations', vus: 1, iterations: 5, maxDuration: '30s' } },
  thresholds: { rate_limited_responses: ['count>=1'], unexpected_responses: ['count==0'] },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const response = http.post(`${baseUrl}/api/events/${fixture.eventId}/reservations`, '{}', {
    headers: authHeaders(tokenFor(0)),
    responseCallback: http.expectedStatuses(201, 409, 429),
    tags: { endpoint: 'rate-limit-smoke' },
  });
  if (response.status === 429) limited.add(1);
  else if (response.status !== 201 && response.status !== 409) unexpected.add(1);
}
