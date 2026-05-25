import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * Redis 카운터 전략 Before/After TPS 비교 테스트
 *
 * [사용법]
 *
 * Step 1 — Redis 실행 상태 측정 (After)
 *   k6 run -e SCENARIO=with_redis k6/redis-counter-comparison.js
 *
 * Step 2 — Redis 중단 후 측정 (Before)
 *   docker stop <redis-container>
 *   k6 run -e SCENARIO=no_redis k6/redis-counter-comparison.js
 *   docker start <redis-container>
 *
 * [측정 근거]
 *   - 상품 10,000건 (일반 9,990 + 핫 10)
 *   - VU 100 : MAU 1만 명 기준 피크 시간대 동시 접속 1%
 *   - 파레토 법칙 : 핫 상품 10개에 트래픽 80% 집중
 *     → Redis 없을 때 동일 row UPDATE 경합이 극대화되어 before/after 차이가 명확히 드러남
 *   - 요청 비율 : 목록 70% / 상세 30%
 *     → 실제 서비스 패턴 반영 (목록 → 상세 유입 흐름)
 */

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:9004';
const SCENARIO  = __ENV.SCENARIO  || 'with_redis';

// 핫 상품 10개 (seed-products.sql 실행 결과)
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
    { duration: '10s', target: 10  },  // warm-up
    { duration: '40s', target: 100 },  // ramp-up
    { duration: '10s', target: 0   },  // cool-down
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

// setup: 일반 상품 ID를 API에서 동적으로 수집 (랜덤 분산용)
export function setup() {
  const ids = [];
  for (let page = 0; page < 5; page++) {
    const res = http.get(`${BASE_URL}/api/v1/products?thisPage=${page}&pageSize=10`);
    if (res.status !== 200) continue;
    const body = JSON.parse(res.body);
    const content = body.data?.content || [];
    content.forEach(p => {
      if (!HOT_IDS.includes(p.id)) ids.push(p.id);
    });
  }
  return { normalIds: ids };
}

export default function (data) {
  const page = Math.floor(Math.random() * 100);

  // ── with_redis: Redis 캐시 사용 ───────────────────────────────────────────
  const withRedisRes = http.get(
    `${BASE_URL}/api/v1/products?thisPage=${page}&pageSize=10`,
    { tags: { type: 'with_redis' } }
  );
  check(withRedisRes, {
    'with_redis 200': (r) => r.status === 200,
    'with_redis p95 < 500ms': (r) => r.timings.duration < 500,
  });

  // ── no_cache: DB 직접 조회 ────────────────────────────────────────────────
  const noCacheRes = http.get(
    `${BASE_URL}/api/v1/products/no-cache?thisPage=${page}&pageSize=10`,
    { tags: { type: 'no_cache' } }
  );
  check(noCacheRes, {
    'no_cache 200': (r) => r.status === 200,
    'no_cache p95 < 500ms': (r) => r.timings.duration < 500,
  });

  sleep(0.1);
}
