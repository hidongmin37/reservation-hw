import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    checkout_steady: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      exec: 'checkout',
    },

    booking_burst: {
      executor: 'shared-iterations',
      vus: 1000,
      iterations: 1000,
      maxDuration: '30s',
      startTime: '5s',
      exec: 'booking',
    },
  },

  thresholds: {
    'http_req_duration{scenario:checkout_steady}': ['p(99)<500'],
    'http_req_duration{status:201}': ['p(99)<3000'],
    'http_req_duration{status:409}': ['p(99)<5000'],
    'checks': ['rate>0.99'],
  },
};

export function checkout() {
  const userIds = [1, 2, 3, 100, 200];
  const userId = userIds[Math.floor(Math.random() * userIds.length)];
  const res = http.get(`${BASE_URL}/checkout?productId=1&userId=${userId}`);
  check(res, {
    'checkout 200': (r) => r.status === 200,
  });
}

export function booking() {
  const userId = __VU * 1000 + __ITER + 10000;

  const payload = JSON.stringify({
    userId: userId,
    productId: 1,
    paymentMethods: [{ method: 'YPAY', amount: 50000 }],
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uuidv4(),
    },
  };

  const res = http.post(`${BASE_URL}/booking`, payload, params);
  check(res, {
    'booking 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}
