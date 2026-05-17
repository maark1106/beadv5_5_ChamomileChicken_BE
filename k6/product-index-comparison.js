import http from 'k6/http';
import { check } from 'k6';

/**
 * 복합 인덱스 Before/After TPS 비교 테스트
 *
 * [사용법]
 *
 * Step 1 — 인덱스 없는 상태에서 측정 (Workbench에서 인덱스 DROP 후 실행)
 *   k6 run -e SCENARIO=no_index k6/product-index-comparison.js
 *
 * Step 2 — 복합 인덱스 추가 후 측정
 *   CREATE INDEX idx_category_region ON products (category, region, status, delete_dt, reg_dt);
 *   k6 run -e SCENARIO=composite_index k6/product-index-comparison.js
 *
 * Step 3 — (선택) 커버링 인덱스 비교
 *   DROP INDEX idx_category_region ON products;
 *   CREATE INDEX idx_covering ON products (category, region, status, delete_dt, reg_dt, id, title, price, thumbnail_path);
 *   k6 run -e SCENARIO=covering_index k6/product-index-comparison.js
 *
 * [환경변수]
 *   BASE_URL  : 기본값 http://localhost:9004
 *   SCENARIO  : no_index | composite_index | covering_index (레이블용, 실제 동작은 동일)
 *   CATEGORY  : 필터 카테고리 (기본값 SPORTS)
 *   REGION    : 필터 지역     (기본값 GANGNAM)
 *   PAGE      : 페이지 번호   (기본값 0)
 *   SIZE      : 페이지 크기   (기본값 10)
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9004';
const SCENARIO = __ENV.SCENARIO || 'no_index';
const CATEGORY = __ENV.CATEGORY || 'SPORTS';
const REGION   = __ENV.REGION   || 'GANGNAM';
const PAGE     = __ENV.PAGE     || '0';
const SIZE     = __ENV.SIZE     || '10';

// ── 이론적 근거 ──────────────────────────────────────────────────────────────
//
//  products 테이블: 500만 건 (category 5종 × region 25개 × 40,000건)
//
//  [no_index]
//    type=ALL → Full Table Scan (5,000,000 rows)
//    Extra=Using filesort → 정렬 추가 비용
//    예상: 단일 쿼리 수백ms~수초 → 낮은 TPS
//
//  [composite_index] idx_category_region (category, region, status, delete_dt, reg_dt)
//    type=ref → B-Tree Range Scan (~40,000 rows → LIMIT 10으로 조기 종료)
//    Extra=Backward index scan → reg_dt DESC 정렬을 인덱스에서 직접 처리
//    예상: 단일 쿼리 수ms → 높은 TPS
//
//  [covering_index] + (id, title, price, thumbnail_path)
//    Extra=Using index → heap lookup 제거
//    이론: composite_index 대비 10~20% 추가 향상 (heap 접근 제거)
//    트레이드오프: 인덱스 크기 ~3배 증가 → 쓰기 오버헤드 증가
//
// ─────────────────────────────────────────────────────────────────────────────

// VU 단계: 1 → 5 → 10 → 30 → 50 → 100
// no_index는 50 이상에서 timeout 예상, composite_index는 100에서도 안정적이어야 함
export const options = {
  stages: [
    { duration: '10s', target: 1   },  // warm-up
    { duration: '30s', target: 1   },  // baseline
    { duration: '10s', target: 5   },
    { duration: '30s', target: 5   },
    { duration: '10s', target: 10  },
    { duration: '30s', target: 10  },
    { duration: '10s', target: 30  },
    { duration: '30s', target: 30  },
    { duration: '10s', target: 50  },
    { duration: '30s', target: 50  },
    { duration: '10s', target: 100 },
    { duration: '30s', target: 100 },
    { duration: '10s', target: 0   },  // cool-down
  ],
  thresholds: {
    // no_index: 완전한 실패 기준만 (관찰용)
    // composite_index: p95 < 500ms 기대
    http_req_failed:   ['rate<0.5'],
    http_req_duration: ['p(99)<30000'],
  },
};

const URL = `${BASE_URL}/api/v1/products/filter?category=${CATEGORY}&region=${REGION}&page=${PAGE}&size=${SIZE}`;

export default function () {
  const res = http.get(URL, {
    tags: { scenario: SCENARIO },
  });

  check(res, {
    'status 200':    (r) => r.status === 200,
    'p95 < 500ms':   (r) => r.timings.duration < 500,
    'p95 < 3000ms':  (r) => r.timings.duration < 3000,
  });
}