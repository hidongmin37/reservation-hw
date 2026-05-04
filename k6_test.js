import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 1000,
      iterations: 1000,
      maxDuration: '30s',
    },
  },
  thresholds: {
    'http_req_duration{status:201}': ['p(99)<3000'],
    'http_req_duration{status:409}': ['p(99)<5000'],
    'checks': ['rate>0.99'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
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
    'status 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}
