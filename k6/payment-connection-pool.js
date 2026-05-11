import http from 'k6/http';
import { check } from 'k6';

/**
 * HikariCP 커넥션 풀 Before/After 비교 테스트
 *
 * [Phase 1] 꺾이는 구간 파악 — Wide Scan
 *   k6 run -e SCENARIO=before -e STAGE=scan \
 *          -e EMAIL=<email> -e PASSWORD=<pw> \
 *          -e SCHEDULE_ID=<uuid> -e PRODUCT_ID=<uuid> -e PRODUCT_PRICE=1000 \
 *          k6/payment-connection-pool.js
 *   → VU: 10 → 50 → 100 → 200 → 500 (넓은 간격으로 어느 구간에서 꺾이는지 파악)
 *
 * [Phase 2] 꺾이는 지점 정밀 측정 — Fine Scan (Zoom)
 *   k6 run -e SCENARIO=before -e STAGE=zoom \
 *          -e ZOOM_START=100 -e ZOOM_END=200 \
 *          -e EMAIL=<email> -e PASSWORD=<pw> \
 *          -e SCHEDULE_ID=<uuid> -e PRODUCT_ID=<uuid> -e PRODUCT_PRICE=1000 \
 *          k6/payment-connection-pool.js
 *   → ZOOM_START~ZOOM_END 구간을 5등분하여 촘촘하게 측정
 *
 * [After 비교]
 *   k6 run -e SCENARIO=after -e STAGE=scan ...
 */

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const EMAIL     = __ENV.EMAIL     || 'test@test.com';
const PASSWORD  = __ENV.PASSWORD  || 'test1234!';

const SCENARIO = __ENV.SCENARIO || 'before';
const STAGE    = __ENV.STAGE    || 'scan';

const PRODUCT_SCHEDULE_ID = __ENV.SCHEDULE_ID  || '';
const PRODUCT_ID          = __ENV.PRODUCT_ID   || '';
const PRODUCT_PRICE       = parseInt(__ENV.PRODUCT_PRICE || '1000');

const ZOOM_START = parseInt(__ENV.ZOOM_START || '100');
const ZOOM_END   = parseInt(__ENV.ZOOM_END   || '200');

// ── 이론적 근거 ──────────────────────────────────────────────────────────────
//
//  HikariCP pool=10, connection-timeout=3000ms
//  MockTossPaymentClient: 100~500ms random (avg ≈ 300ms)
//
//  [Before] @Transactional이 외부 호출 포함 전체를 감쌈
//    커넥션 점유 시간 ≈ DB조회(5ms) + HTTP validate(5ms) + PG호출(300ms) + DB저장(5ms) ≈ 315ms
//
//    포화점 VU  = pool × 점유시간 = 10 × (1 / 0.315s) → 역산: 10 × 0.315 ≈ 10VU  ← pool 100%
//    timeout VU = 포화점 + (3000ms / 31.5ms) ≈ 107VU                               ← 실패 시작
//
//    VU=10  대기 0ms     pool 정확히 포화, 기준선
//    VU=50  대기 1,240ms timeout 없음, latency 증가
//    VU=100 대기 2,790ms timeout 직전
//    VU=200 대기 5,890ms timeout 발생 구간
//    VU=500 대기 15,190ms 거의 전부 실패
//
//  [After] 외부 호출 중 커넥션 미점유
//    커넥션 점유 시간 ≈ 2ms → 이론 max TPS ≈ 5,000 → 모든 단계에서 안정적이어야 함
//
// ─────────────────────────────────────────────────────────────────────────────

// scan VU: 기본값 [10,50,100,200,500], env로 덮어쓰기 가능
// 예) -e SCAN_VUS=500,750,1000,1500,2000
const SCAN_VUS = (__ENV.SCAN_VUS || '10,50,100,200,500')
  .split(',')
  .map(v => parseInt(v.trim()));

// zoom: ZOOM_START~ZOOM_END 를 5등분 (양 끝 포함)
function buildZoomVUs() {
  const steps    = 5;
  const interval = Math.round((ZOOM_END - ZOOM_START) / (steps - 1));
  const vus = [];
  for (let i = 0; i < steps - 1; i++) {
    vus.push(ZOOM_START + interval * i);
  }
  vus.push(ZOOM_END);
  return vus;
}

function buildStages() {
  if (STAGE === 'scan') {
    // 각 VU 단계: 10s 램프 → 45s 유지
    // 총 소요: (10s + 45s) × 5 + 10s(쿨다운) ≈ 5분
    const stages = [];
    for (const vu of SCAN_VUS) {
      stages.push({ duration: '10s', target: vu });
      stages.push({ duration: '45s', target: vu });
    }
    stages.push({ duration: '10s', target: 0 });
    return stages;
  }

  if (STAGE === 'zoom') {
    // 각 VU 단계: 10s 램프 → 50s 유지 (정밀 측정)
    // 총 소요: (10s + 50s) × 5 + 10s(쿨다운) ≈ 5분
    const stages = [];
    for (const vu of buildZoomVUs()) {
      stages.push({ duration: '10s', target: vu });
      stages.push({ duration: '50s', target: vu });
    }
    stages.push({ duration: '10s', target: 0 });
    return stages;
  }

  // 개별 단계 실행 (레거시 호환)
  const legacyMap = {
    smoke:  { vus: 3,   duration: '30s' },
    load:   { vus: 30,  duration: '1m'  },
    stress: { vus: 150, duration: '2m'  },
  };
  const s = legacyMap[STAGE];
  if (!s) throw new Error(`알 수 없는 STAGE: ${STAGE}`);
  return [
    { duration: '10s',       target: s.vus },
    { duration: s.duration,  target: s.vus },
    { duration: '10s',       target: 0     },
  ];
}

// ── thresholds ────────────────────────────────────────────────────────────────
//
//  scan: 실패 예상 구간(VU=200,500) 포함 → pass/fail 기준 무의미, 관찰용으로 느슨하게
//  zoom: 정밀 측정 목적 → threshold 없음, 숫자 자체를 분석
//
// ─────────────────────────────────────────────────────────────────────────────
const THRESHOLDS = {
  scan:   { http_req_duration: ['p(95)<5000'], http_req_failed: ['rate<0.9'] },
  zoom:   {},
  smoke:  { http_req_duration: ['p(95)<5000'], http_req_failed: ['rate<0.1'] },
  load:   { http_req_duration: ['p(95)<5000'], http_req_failed: ['rate<0.1'] },
  stress: { http_req_duration: ['p(95)<5000'], http_req_failed: ['rate<0.1'] },
};

export const options = {
  stages:     buildStages(),
  thresholds: THRESHOLDS[STAGE] || {},
};

// ── setup: 테스트 데이터 준비 ──────────────────────────────────────────────────
//
//  setup count 근거:
//
//  scan (400개):
//    VU=500이 최대, 매 순간 pool=10개 row를 동시에 읽음
//    row 다양성 부족 → 동일 row 집중 → 비정상적 lock/cache 패턴
//    최소 필요: pool × 다양성 배율(10배) = 100개
//    여유분 4배 적용: 400개 (setup 소요 약 80초)
//
//  zoom (min(ZOOM_END×2, 150)개):
//    탐색 VU 범위가 좁음 (예: 100~200)
//    confirmPerfBefore/After는 Payment 상태를 바꾸지 않아 동일 orderId 재사용 가능
//    ZOOM_END=200 기준 max 150개로 충분
//
// ─────────────────────────────────────────────────────────────────────────────

function getSetupCount() {
  if (STAGE === 'scan') return 400;
  if (STAGE === 'zoom') return Math.min(ZOOM_END * 2, 150);
  return 50;
}

export function setup() {
  const res = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email:    EMAIL,
    password: PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' } });

  const token = res.json('data.accessToken');
  if (!token) {
    throw new Error(`로그인 실패 — status=${res.status} body=${res.body}`);
  }

  const count = getSetupCount();
  console.log(`[setup] 로그인 성공 | scenario=${SCENARIO} stage=${STAGE} | 주문 ${count}개 생성 시작...`);

  const orders = [];
  for (let i = 0; i < count; i++) {
    const orderId = createOrder(token);
    if (!orderId) continue;

    preparePayment(token, orderId);
    orders.push(orderId);

    if ((i + 1) % 50 === 0) {
      console.log(`[setup] ${i + 1}/${count} 완료`);
    }
  }

  console.log(`[setup] 완료 — ${orders.length}개 준비됨`);
  return { token, orders };
}

export default function (data) {
  const headers = {
    Authorization:  `Bearer ${data.token}`,
    'Content-Type': 'application/json',
  };

  const idx     = (__VU - 1) * 100 + __ITER;
  const orderId = data.orders[idx % data.orders.length];
  const endpoint = SCENARIO === 'before'
    ? `${BASE_URL}/api/v1/payments/perf/confirm/before`
    : `${BASE_URL}/api/v1/payments/perf/confirm/after`;

  const res = http.post(endpoint, JSON.stringify({
    orderId:    orderId,
    paymentKey: 'perf-key',
    amount:     PRODUCT_PRICE,
  }), { headers });

  if (res.status !== 200) {
    console.error(`[confirm] 실패 status=${res.status} body=${res.body}`);
  }

  check(res, {
    'status 200': (r) => r.status === 200,
    'p95 < 3s':   (r) => r.timings.duration < 3000,
  });
}

// ── 헬퍼 ────────────────────────────────────────────────────────────────────

function createOrder(token) {
  const res = http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify({
    productId:         PRODUCT_ID,
    productScheduleId: PRODUCT_SCHEDULE_ID,
    quantity:          1,
    productPrice:      PRODUCT_PRICE,
    depositAmount:     0,
  }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization:  `Bearer ${token}`,
    },
  });

  if (res.status !== 201) {
    console.error(`[createOrder] 실패 status=${res.status} body=${res.body}`);
    return null;
  }
  const orderId = res.json('id');
  if (!orderId) {
    console.error(`[createOrder] orderId 파싱 실패 body=${res.body}`);
  }
  return orderId;
}

function preparePayment(token, orderId) {
  const res = http.post(`${BASE_URL}/api/v1/payments/prepare`, JSON.stringify({
    orderId:       orderId,
    productId:     PRODUCT_ID,
    paymentMethod: 'TOSS',
    paymentAmount: PRODUCT_PRICE,
    depositAmount: 0,
  }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization:  `Bearer ${token}`,
    },
  });

  if (res.status !== 200 && res.status !== 201) {
    console.error(`[preparePayment] 실패 status=${res.status} body=${res.body}`);
  }
}
