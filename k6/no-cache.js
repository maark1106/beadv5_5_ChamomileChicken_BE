import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * [Before] Redis 없이 DB 직접 조회 - TPS 측정
 * k6 run k6/no-cache.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9004';

export const options = {
  stages: [
    { duration: '10s', target: 10  },
    { duration: '40s', target: 100 },
    { duration: '10s', target: 0   },
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {
  const page = Math.floor(Math.random() * 100);

  const res = http.get(`${BASE_URL}/api/v1/products/no-cache?thisPage=${page}&pageSize=10`);
  check(res, { '200 OK': (r) => r.status === 200 });

  sleep(0.1);
}
