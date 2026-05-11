# 결제 승인 API 커넥션 풀 병목 — 분산 추적 기반 원인 분석

## 개요

결제 승인 API에 부하를 단계적으로 증가시키면서 성능 한계 지점을 측정하고,
실패 구간에서 분산 추적과 커넥션 풀 지표를 함께 분석하여 병목 원인을 특정하고 개선했다.

---

## 테스트 환경

| 항목 | 설정 |
|------|------|
| 부하 도구 | k6 |
| 대상 API | `POST /api/v1/payments/perf/confirm/before` |
| PG 클라이언트 | `MockTossPaymentClient` — 실측 기준 100~500ms 랜덤 지연 |
| HikariCP pool | `maximum-pool-size: 10`, `connection-timeout: 3000ms` |
| 분산 추적 | Micrometer + Brave + Grafana Tempo |

```
k6 stages (before 시나리오)
  smoke  :  VU=3,   30s
  load   :  VU=30,  1m
  stress :  VU=150, 2m
```

---

## 1단계 — 부하 단계별 기준 성능 측정

VU를 단계적으로 증가시키면서 p95 응답시간과 실패율이 급증하는 한계 지점을 찾았다.

| 단계 | VU | 실패율 | avg | p95 | 비고 |
|------|----|--------|-----|-----|------|
| smoke  | 3   | 0%      | ~320ms | ~490ms | 정상 |
| load   | 30  | 0%      | 789ms  | 1.12s  | 지연 증가 |
| stress | 150 | **26.85%** | ~2.4s | 3.27s | **실패 발생** |

stress 단계에서 실패율이 급증하고 p95가 3초를 초과했다.

---

## 2단계 — 실패 구간 분석: 분산 추적 (Grafana Tempo)

실패가 발생한 stress 구간에서 traceId를 기준으로 요청 흐름을 확인했다.

```
POST /api/v1/payments/confirm [340ms]
 ├─ http.get → order-service 검증     5ms   (1.5%)
 ├─ toss.pg.confirm                 320ms  (94%)   ← 대부분의 시간
 └─ JDBC: findPayment                 5ms   (1.5%)
```

**결제 승인 요청 시간의 94%가 외부 PG 호출 구간에서 발생**하고 있었다.

다만 특정 구간의 실행 시간이 길다는 것만으로는 DB 커넥션 고갈의 원인이라고 단정할 수 없다.
외부 호출 지연과 커넥션 점유가 실제로 겹치는지 별도 지표로 확인이 필요했다.

---

## 3단계 — 실패 구간 분석: HikariCP 지표

같은 시간대의 HikariCP 지표를 확인했다.

```
hikaricp_connections_active   → VU 증가와 함께 max(10)에 근접
hikaricp_connections_pending  → active가 max에 근접하는 시점부터 급증
hikaricp_connections_timeout  → pending 증가 후 3초 경과 시 에러로 전환
```

active connection이 풀 상한에 근접하는 시점부터 pending이 쌓이고,
이후 connectionTimeout(3s) 초과로 인한 실패가 발생하는 패턴이었다.

---

## 4단계 — 두 지표 연결: 원인 특정

분산 추적과 커넥션 풀 지표를 연결하면 다음과 같은 구조가 나온다.

```
외부 PG 호출이 300ms 이상 소요  (Tempo에서 확인)
  +
active connection이 max에 머무름 (HikariCP에서 확인)
  ↓
PG 호출 대기 시간 동안 DB 커넥션이 반납되지 않고 있을 가능성
```

코드를 확인하자 원인이 명확했다.

```java
@Transactional              // ← 커넥션 획득
public void confirm() {
    findPayment();          // DB 조회   ~5ms
    orderPort.validate();   // HTTP 호출  ~5ms  ← 커넥션 점유 중
    pgGateway.confirm();    // PG 호출  ~300ms  ← 커넥션 점유 중
    savePayment();          // DB 저장   ~5ms
}                           // ← 커넥션 반납
```

`@Transactional`이 외부 API 호출을 포함한 전체 흐름을 감싸고 있어,
PG 응답 대기 시간(~300ms) 동안 DB 커넥션이 반납되지 않는 구조였다.

```
커넥션 점유 시간 ≈ 315ms
최대 TPS = pool(10) / 점유시간(0.315s) ≈ 32 TPS

stress 150VU 부하 → ~40 TPS 이상 요구
→ pool 고갈, pending 증가, timeout 발생
```

---

## 개선 — 외부 API 호출을 트랜잭션 밖으로 분리

```java
// @Transactional 없음 — 외부 호출 중 커넥션 미점유
public void confirm() {
    findPayment();          // 짧은 트랜잭션으로 커넥션 획득 후 즉시 반납
    orderPort.validate();   // HTTP 호출  ← 커넥션 없음
    pgGateway.confirm();    // PG 호출   ← 커넥션 없음
    onSuccess();            // 짧은 트랜잭션에서 DB 저장 후 반납
}

@Transactional
private void onSuccess() {
    savePayment();          // DB 저장 ~5ms
}
```

추가로 `spring.jpa.open-in-view: false` 설정이 필요하다.
OSIV가 활성화되어 있으면 `@Transactional`을 제거하더라도
HTTP 요청 전체 구간 동안 EntityManager(= DB 커넥션)가 유지되어 동일한 문제가 발생한다.

```
커넥션 점유 시간 ≈ 7ms (DB 작업만)
최대 TPS = pool(10) / 점유시간(0.007s) ≈ 1,400 TPS
```

---

## 결과

동일한 stress 조건(VU=150)에서 before/after를 비교했다.

| 지표 | Before | After |
|------|--------|-------|
| 실패율 | 26.85% | **0%** |
| avg 응답시간 | ~2.4s | **311ms** |
| p95 응답시간 | 3.27s | **495ms** |
| TPS | ~6 | **413** |
| `hikaricp_connections_pending` | VU 증가 시 급증 | 안정 유지 |
| `hikaricp_connections_timeout` | 발생 | 없음 |
| DB 커넥션 점유 시간 | ~315ms | ~7ms |

개선 후 병목이 DB 커넥션 풀에서 PG 호출 지연(불가피한 외부 의존)으로 이동했다.
avg 311ms는 PG mock 딜레이(100~500ms) 평균에 해당하며,
이는 DB 커넥션 관점에서 더 이상 개선할 여지가 없는 구조임을 의미한다.

---

## 구현 참고

### 테스트 엔드포인트

| 구분 | 엔드포인트 | 설명 |
|------|-----------|------|
| Before | `POST /api/v1/payments/perf/confirm/before` | `@Transactional`이 PG 호출 포함 |
| After | `POST /api/v1/payments/perf/confirm/after` | DB 작업만 `@Transactional`, OSIV=false |

### HikariCP 설정

```yaml
spring:
  jpa:
    open-in-view: false
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 3000
      idle-timeout: 600000
```

### 분산 추적 설정

Spring Boot 4.x는 Zipkin auto-configuration이 제거되어 수동 구성이 필요하다.
`TracingConfig`에서 `URLConnectionSender` → `AsyncZipkinSpanHandler` → `Tracing` → `BraveTracer` 순으로 빈을 구성하고,
`ObservationHandler.FirstMatchingCompositeObservationHandler`로 핸들러를 묶어 observation당 하나의 핸들러만 실행되도록 한다.

```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
implementation 'io.zipkin.reporter2:zipkin-reporter-brave'
implementation 'io.zipkin.reporter2:zipkin-sender-urlconnection'
```