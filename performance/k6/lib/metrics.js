import { Counter, Trend } from 'k6/metrics';

export const expectedBusinessResults = new Counter('expected_business_results');
export const unexpectedResponses = new Counter('unexpected_responses');
export const serverFailures = new Counter('server_failures');
export const clientFailures = new Counter('client_failures');
export const timeouts = new Counter('timeouts');
export const successfulMutationLatency = new Trend('successful_mutation_latency', true);
export const replayLatency = new Trend('idempotency_replay_latency', true);

export function classify(response, expectedStatuses, expectedBusinessCodes = []) {
  let code = '';
  try {
    code = response.json('code') || '';
  } catch (_) {
    // A response need not contain JSON to be classified by status.
  }

  if (expectedBusinessCodes.includes(code)) {
    expectedBusinessResults.add(1);
    return 'business';
  }
  if (expectedStatuses.includes(response.status)) {
    return 'success';
  }
  if (response.error_code === 1050 || String(response.error || '').toLowerCase().includes('timeout')) {
    timeouts.add(1);
  } else if (response.status >= 500) {
    serverFailures.add(1);
  } else if (response.status === 0) {
    clientFailures.add(1);
  } else {
    unexpectedResponses.add(1);
  }
  return 'unexpected';
}
