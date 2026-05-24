# Redis 카운터를 활용한 좋아요/조회수 성능 개선

## 배경

상품 조회수와 좋아요 수는 성격이 다른 두 가지 카운터다.

| 항목 | 좋아요 수 | 조회수 |
|------|----------|--------|
| 성격 | 명시적 사용자 상태 | 통계성 데이터 |
| 정합성 요구 | 높음 ("내가 눌렀는가?") | 낮음 (약간의 오차 허용) |
| 쓰기 빈도 | 낮음 | 매우 높음 (조회마다 발생) |
| DB 반영 전략 | 즉시 반영 | 주기적 배치 반영 (Write-Behind) |

두 카운터를 같은 방식으로 처리하면 문제가 생긴다.
- 조회수를 DB에 매번 UPDATE하면 트래픽 증가 시 Row-level lock 경합 발생
- 좋아요를 비동기로만 처리하면 "내가 눌렀는가?" 조회의 정확성이 떨어짐

따라서 데이터 특성에 맞게 전략을 분리했다.

---

## 핵심 설계 원칙

```
상품 정적 정보 (제목, 가격, 설명 등) → DB에서 조회
상품 동적 카운터 (좋아요 수, 조회수) → Redis에서 조회
→ 애플리케이션(Spring Boot)에서 DTO로 조합해서 반환
```

DB의 `view_count`는 Write-Behind 전략상 최대 1시간 전 데이터다.
DB에서 조회수를 그대로 반환하면 사용자는 멈춰있는 숫자를 보게 된다.
최신 카운터는 반드시 Redis에서 읽어야 한다.

---

## Redis 키 설계

```
like_count:product:{productId}   → 상품 좋아요 수
view_count:product:{productId}   → 상품 조회수
```

---

## 1. 좋아요 전략

### 쓰기

```
좋아요 등록
→ DB products_likes 테이블에 (userId, productId) 즉시 저장
→ Redis like_count INCR
→ 응답

좋아요 취소
→ DB soft delete
→ Redis like_count DECR
→ 응답
```

좋아요 상태(내가 눌렀는가?)는 DB가 원본이므로 DB에 동기적으로 저장한다.
Redis INCR/DECR 실패 시 조용히 무시하고, 보정 스케줄러가 수렴시킨다.

**Partial failure 처리:**
DB 저장 성공 + Redis INCR 실패가 발생할 수 있다.
트랜잭션으로 묶을 수 없으므로 이 오차는 허용하고, 매일 새벽 보정 스케줄러가 DB 기준으로 재동기화한다.

### 읽기

```
좋아요 수 조회
→ Redis like_count 조회
→ 있으면 Redis 값 반환
→ 없으면 DB COUNT(*) 조회 → SETNX로 캐싱 → 반환
```

Redis miss 시 `SETNX`(SET if Not Exists)를 사용해 동시 요청이 몰릴 때 초기화 race condition을 방지한다.

### 보정 스케줄러

```
매일 새벽 3시
→ 전체 상품 DB COUNT(*) 조회
→ Redis like_count 재동기화
```

---

## 2. 조회수 전략

### 쓰기

```
상품 상세 조회 시
→ Redis view_count 키 존재 확인
→ 없으면: DB view_count 값으로 SETNX 초기화
→ Redis INCR
→ 응답
```

SETNX 초기화 예시:
```
DB view_count = 100, Redis 키 없음

첫 번째 조회 요청
→ SETNX view_count:product:{id} "100"  (한 번만 성공)
→ INCR
→ Redis = 101
```

DB에는 주기적으로 반영한다.
```
매시 정각
→ Redis view_count → DB view_count UPDATE
→ Redis 키가 없는 상품은 skip
```

### 읽기

```
상세 조회
→ Redis view_count 있으면 Redis 값 반환
→ 없으면 DB view_count 값으로 SETNX 초기화 후 INCR → 반환

목록 조회 (write-through)
→ Redis multiGet으로 배치 조회
→ miss 항목은 Product 엔티티의 view_count로 SETNX 캐싱 → 반환
→ 이후 조회부터 Redis hit
```

목록 조회 시 miss가 발생해도 이미 로드된 Product 엔티티에 view_count가 있으므로 추가 DB 쿼리 없이 Redis에 등록된다.

### Redis 장애 처리

Redis 장애 시 조회수 INCR 실패는 조용히 무시한다.
조회 자체를 막지 않으며, DB의 `view_count` 값을 fallback으로 반환한다.

```java
try {
    redisTemplate.opsForValue().increment(key);
} catch (Exception e) {
    log.warn("Redis view_count INCR 실패: {}", e.getMessage());
    // 조회 응답은 정상적으로 반환
}
```

---

## 3. 목록 조회 최적화 (multiGet)

상품 목록 조회 시 N개 상품의 카운터를 개별 조회하면 Redis 요청이 N번 발생한다.
`multiGet`으로 한 번에 배치 조회한다.

```
상품 목록 10개 조회
→ productId 10개 추출
→ Redis multiGet(["like_count:product:id1", ..., "like_count:product:id10"]) → 1번
→ Redis multiGet(["view_count:product:id1", ..., "view_count:product:id10"]) → 1번
→ DB 결과 + Redis 결과 조합
→ 응답
```

**좋아요 miss:** DB에서 IN + GROUP BY 배치 조회로 보완한다.
**조회수 miss:** 이미 로드된 Product 엔티티의 `view_count` 값을 `SETNX`로 Redis에 캐싱 후 반환한다. 추가 DB 쿼리 없음.
`searchMy`(ES 기반)는 ProductDocument에 view_count가 없으므로 write-through 미적용, 0을 반환한다.

---

## 4. 트레이드오프 정리

| 항목 | 선택 | 이유 |
|------|------|------|
| 좋아요 DB 즉시 반영 | 유지 | 상태 정확성이 핵심 |
| 조회수 Write-Behind | 선택 | 매번 UPDATE 시 DB 부하 |
| 조회수 목록 miss 시 write-through | 선택 | Product 엔티티 재활용, 추가 쿼리 없음 |
| Partial failure 허용 (좋아요) | 허용 | 보정 스케줄러로 수렴 |
| 조회수 최대 1시간 손실 허용 | 허용 | 통계성 데이터이므로 허용 범위 |
| Redis 장애 시 fallback | DB 값 반환 | 서비스 가용성 우선 |

---

## 5. 성능 비교 테스트

### 테스트 목적

Redis 카운터 전략의 실제 효과를 수치로 검증한다.

좋아요/조회수 Redis 캐싱의 이점을 두 가지로 나눠서 생각했다.

| 관점 | 내용 | 검증 가능 여부 |
|------|------|--------------|
| 쓰기 경합 감소 | 핫 상품 동시 UPDATE → row lock 경합 → Redis INCR으로 제거 | ✅ 수치로 명확히 드러남 |
| 읽기 부하 감소 | DB COUNT(*) → Redis GET으로 대체 | ⚠️ 현재 데이터 규모에서 차이 미미 |

읽기 관점에서는 어차피 상품 정보 조회를 위해 DB에 접근하므로 카운터 읽기 최적화 효과가 전체 응답시간에서 차지하는 비율이 작다. 또한 favorites 테이블 데이터가 적으면 DB COUNT 자체도 빠르기 때문에 현재 규모에서 극적인 차이를 내기 어렵다.

따라서 **쓰기 경합 제거** 를 핵심 검증 포인트로 삼았다.

---

### 비교 시나리오

**Before**: 상세 조회 시 DB에 직접 UPDATE
```sql
UPDATE products SET view_count = view_count + 1 WHERE id = ?
```

**After**: 상세 조회 시 Redis INCR
```
INCR view_count:product:{id}
```

---

### 테스트 설계

핫 상품 1개에 트래픽을 집중시켜 row lock 경합을 극대화했다.

```
VU: 100
sleep: 없음 (최대한 빠르게 요청)
대상: 동일한 상품 1개에 모든 트래픽 집중
→ 100 VU가 같은 row를 동시에 UPDATE → InnoDB row lock 경합 극대화
```

sleep을 제거하고 단일 상품에 집중한 이유:
- sleep이 있으면 VU당 요청 주기가 길어져 동시 경합이 거의 발생하지 않음
- 여러 상품에 분산하면 경합이 희석되어 차이가 드러나지 않음

---

### 비교 엔드포인트

| | 엔드포인트 | view_count 처리 |
|--|-----------|----------------|
| Before | `GET /api/v1/products/{id}/no-cache` | DB UPDATE (매 요청마다) |
| After | `GET /api/v1/products/{id}` | Redis INCR |

`/no-cache` 엔드포인트는 성능 비교 목적으로만 존재한다.

---

### k6 스크립트

```
k6/detail-no-cache.js   → Before 측정
k6/detail-with-redis.js → After 측정
```

---

### 읽기 비교를 별도로 측정하지 않은 이유

읽기 관점(DB COUNT vs Redis GET)의 차이는 다음 조건에서만 드러난다.

- favorites 테이블이 수십만 건 이상 쌓여 DB COUNT가 느려질 때
- 또는 product_id 인덱스가 없어 Full Table Scan이 발생할 때

현재 테스트 환경에서는 인덱스가 있고 데이터가 적어 DB COUNT 자체가 빠르게 동작한다. 읽기 비교보다 쓰기 경합 시나리오에서 차이가 명확하게 드러난다.

---

## 6. 구현 파일 위치

| 역할 | 파일 |
|------|------|
| 좋아요 등록/취소 + Redis INCR/DECR | `FavoriteService.java` |
| 좋아요 수 조회 (Redis 우선) | `FavoriteService.getLikeCount()` |
| 조회수 INCR + 상품 응답 조합 | `ProductService.incrementAndGetViewCount()` |
| 목록 카운터 배치 조회 | `ProductService.batchGetCounts()` |
| 조회수 목록 miss write-through | `ProductService.writeViewCountCache()` |
| Redis 설정 | `config/RedisConfig.java` |
| 좋아요 수 보정 스케줄러 | `scheduled/LikeCountSyncScheduler.java` |
| 조회수 DB 동기화 스케줄러 | `scheduled/ViewCountSyncScheduler.java` |

---

## 6. 스케줄러 상세

### LikeCountSyncScheduler

```
매일 새벽 3시 (cron: 0 0 3 * * *)
→ DB: SELECT product_id, COUNT(*) FROM products_likes WHERE delete_dt IS NULL GROUP BY product_id
→ Redis: SET like_count:product:{id} {count}
```

DB COUNT(*) 결과를 기준으로 Redis 값을 덮어씌워 Partial failure로 누적된 오차를 수렴시킨다.

### ViewCountSyncScheduler

```
매시 정각 (cron: 0 0 * * * *)
→ Redis SCAN view_count:product:* (커서 기반, 100개 단위)
→ 각 키에서 productId 추출, value 읽기
→ DB: UPDATE products SET view_count = {value} WHERE id = {productId}
```

`KEYS *` 대신 `SCAN`을 사용해 Redis를 블로킹하지 않는다.
Redis에 키가 없는 상품(조회된 적 없는 상품)은 자동으로 skip된다.