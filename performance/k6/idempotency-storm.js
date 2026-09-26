import http from 'k6/http';
import exec from 'k6/execution';
import { authHeaders, baseUrl, fixture, thresholds, tokenFor } from './lib/config.js';
import { classify, replayLatency, successfulMutationLatency } from './lib/metrics.js';

const iterations = Number(__ENV.ITERATIONS || 500);
export const options = {
  scenarios: {
    storm: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 100),
      iterations,
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const response = http.post(`${baseUrl}/api/events/${fixture.eventId}/reservations`, '{}', {
    headers: authHeaders(tokenFor(0), 'perf-storm-shared-key'),
    tags: { endpoint: 'idempotency-storm' },
  });
  if (classify(response, [201]) === 'success') {
    (exec.scenario.iterationInTest === 0 ? successfulMutationLatency : replayLatency).add(response.timings.duration);
  }
}
