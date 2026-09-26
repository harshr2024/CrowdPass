import http from 'k6/http';
import { arrivalRate, baseUrl, fixture, thresholds } from './lib/config.js';
import { classify } from './lib/metrics.js';

export const options = {
  scenarios: { reads: arrivalRate(100) }, thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const detail = __ITER % 2 === 1;
  const response = http.get(
    detail ? `${baseUrl}/api/events/${fixture.eventId}` : `${baseUrl}/api/events?size=20`,
    { tags: { endpoint: detail ? 'event-detail' : 'event-list' } },
  );
  classify(response, [200]);
}
