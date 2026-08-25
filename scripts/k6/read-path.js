import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const EMAIL = __ENV.K6_EMAIL || 'owner@example.test';
const PASSWORD = __ENV.K6_PASSWORD || 'test1234';
const THINK = Number(__ENV.K6_THINK || 0.3);

const jsonHeaders = { 'Content-Type': 'application/json' };

export const options = {
  // Ramp so Grafana shows the inflection, not a flat line.
  stages: [
    { duration: '20s', target: 10 },
    { duration: '40s', target: 30 },
    { duration: '40s', target: 50 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

function isProdTarget(url) {
  return /aewol\.store|cloudfront\.net|amazonaws\.com/i.test(url);
}

export function setup() {
  if (isProdTarget(BASE_URL) && __ENV.ALLOW_PROD !== '1') {
    fail('Refusing to hit a public host. Set ALLOW_PROD=1 if you really mean it.');
  }

  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: jsonHeaders, tags: { name: 'login' } },
  );
  check(res, { 'login 200': (r) => r.status === 200 });
  if (res.status !== 200) {
    fail(`login failed: ${res.status} ${res.body}`);
  }
  const token = res.json('result.accessToken');
  if (!token) {
    fail('login response had no result.accessToken');
  }
  return { token };
}

function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json',
  };
}

function get(path, token, name) {
  return http.get(`${BASE_URL}${path}`, {
    headers: authHeaders(token),
    tags: { name },
  });
}

export default function (data) {
  const token = data.token;
  const roll = Math.random();

  if (roll < 0.15) {
    const res = http.get(`${BASE_URL}/api/health`, { tags: { name: 'health' } });
    check(res, { 'health 200': (r) => r.status === 200 });
  } else if (roll < 0.25) {
    const res = http.get(`${BASE_URL}/api/support/faqs`, { tags: { name: 'faqs' } });
    check(res, { 'faqs 200': (r) => r.status === 200 });
  } else if (roll < 0.45) {
    const res = get('/api/users/me', token, 'me');
    check(res, { 'me 200': (r) => r.status === 200 });
  } else if (roll < 0.65) {
    const res = get('/api/wallet', token, 'wallet');
    check(res, { 'wallet 200': (r) => r.status === 200 });
  } else if (roll < 0.8) {
    const res = get('/api/pets', token, 'pets');
    check(res, { 'pets 200': (r) => r.status === 200 });
  } else if (roll < 0.9) {
    const res = get('/api/dashboard/summary', token, 'dashboard');
    check(res, { 'dashboard 200': (r) => r.status === 200 });
  } else {
    const res = get('/api/transactions?size=20', token, 'transactions');
    check(res, { 'transactions 200': (r) => r.status === 200 });
  }

  sleep(THINK);
}
