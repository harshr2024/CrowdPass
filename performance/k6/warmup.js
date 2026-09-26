import http from 'k6/http';
import { baseUrl, fixture } from './lib/config.js';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 20,
      maxVUs: 80,
    },
  },
};

export default function () {
  const path = __ITER % 2 === 0 ? '/api/events?size=20' : `/api/events/${fixture.eventId}`;
  http.get(`${baseUrl}${path}`, { tags: { endpoint: 'warmup-read' } });
}
