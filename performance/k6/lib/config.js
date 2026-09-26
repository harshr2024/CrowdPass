import { SharedArray } from 'k6/data';

export const baseUrl = __ENV.BASE_URL || 'http://app:8080';
export const fixture = JSON.parse(open('/runtime/fixture.json'));
const tokenDocument = JSON.parse(open('/runtime/tokens.json'));

export const tokens = new SharedArray('prepared JWTs', () => tokenDocument.tokens);

export function tokenFor(index) {
  if (tokens.length === 0) {
    throw new Error('no prepared JWTs');
  }
  return tokens[index % tokens.length];
}

export function authHeaders(token, idempotencyKey) {
  const headers = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  return headers;
}

export function arrivalRate(defaultRate, defaultDuration = '20s') {
  const rate = Number(__ENV.RATE || defaultRate);
  return {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: __ENV.DURATION || defaultDuration,
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || Math.max(20, rate)),
    maxVUs: Number(__ENV.MAX_VUS || Math.max(100, rate * 4)),
  };
}

export const thresholds = {
  unexpected_responses: ['count==0'],
  server_failures: ['count==0'],
  client_failures: ['count==0'],
  timeouts: ['count==0'],
};
