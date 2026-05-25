import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * [Before] 상세 조회 시 DB UPDATE view_count - 핫 상품 집중 부하 테스트
 * k6 run k6/detail-no-cache.js
 *
 * 핫 상품 10개에 트래픽 집중 → 동일 row UPDATE 경합 극대화
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9004';

const HOT_IDS = [
  '7d6a65b4-54c4-11f1-970e-32853ff0268d',
  '7d6b1109-54c4-11f1-970e-32853ff0268d',
  '7d6a7a91-54c4-11f1-970e-32853ff0268d',
  '7d6b2407-54c4-11f1-970e-32853ff0268d',
  '7d6a8f74-54c4-11f1-970e-32853ff0268d',
  '7d6ae94e-54c4-11f1-970e-32853ff0268d',
  '7d6b3a01-54c4-11f1-970e-32853ff0268d',
  '7d6aa554-54c4-11f1-970e-32853ff0268d',
  '7d6afd83-54c4-11f1-970e-32853ff0268d',
  '7d6acbd9-54c4-11f1-970e-32853ff0268d',
];

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
  const id = HOT_IDS[0];

  const res = http.get(`${BASE_URL}/api/v1/products/${id}/no-cache`);
  check(res, { '200 OK': (r) => r.status === 200 });
}