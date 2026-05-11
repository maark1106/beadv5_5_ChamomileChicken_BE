# 문제 1. 분산 환경에서 병목 구간 특정 — Micrometer + Grafana Tempo

## 문제 제기

MSA 환경에서는 하나의 요청이 Order, Payment, Product, User 서비스를 거친다.

단순히 "응답이 느리다"만으로는 병목 위치를 알 수 없다.

```
POST /api/v1/payments/confirm [총 315ms]
 어디가 느린가?
 ├─ order-service 호출?
 ├─ Toss PG 호출?
 └─ DB 쿼리?
```

서비스별 로그가 분리되어 있고, 어떤 구간에서 얼마나 걸렸는지 한 눈에 볼 수 없었다.

---

## 해결 방향

**Micrometer + Grafana Tempo** 도입.

```
Micrometer → 각 구간 span 자동 생성 + traceId 전파
Grafana Tempo → span 저장
Grafana → 메트릭(hikariCP, p95)과 트레이스를 한 화면에서 조회
```

---

## 구조

```
요청 진입
  └─ Micrometer가 traceId 생성
       ├─ HTTP 호출 시 헤더에 traceId 자동 전파
       ├─ 각 서비스에서 span 생성 → Tempo 전송
       └─ Grafana에서 traceId로 waterfall 조회
```

---

## 병목 발견 흐름

### 1단계 — Grafana 메트릭에서 이상 징후 확인

```
hikaricp_connections_pending 증가
p95 응답시간 폭증
hikaricp_connections_timeout_total 증가
```

"어디가 문제인가"는 아직 모른다.

### 2단계 — Grafana Tempo에서 waterfall 조회

해당 시점 trace 클릭 → 구간별 실행 시간 확인:

```
POST /api/v1/payments/confirm [315ms]
 ├─ HTTP → order-service 검증    12ms
 ├─ HTTP → Toss PG 승인 요청    300ms  ← 병목
 └─ JDBC: findByOrderId           2ms
```

PG 호출 구간이 300ms 점유하는 것을 시각적으로 확인.

### 3단계 — 두 지표 연결

```
Grafana:  hikariCP pending 증가 (커넥션 대기 발생)
Tempo:    PG 호출이 300ms 점유

→ PG 호출이 @Transactional 범위 안에 있어
  300ms 동안 DB 커넥션을 반납하지 않는 구조
```

### 4단계 — 코드 확인

```java
@Transactional          // ← 커넥션 획득
public void confirm() {
    findPayment();      // DB 조회
    pgGateway.confirm() // PG 호출 300ms — 커넥션 점유 중
    savePayment();      // DB 저장
}                       // ← 커넥션 반납
```

감으로 추정한 게 아니라 **Tempo 구간 측정 + Grafana 지표로 원인을 특정**했다.

---

## 구현

### docker-compose

```yaml
tempo:
  image: grafana/tempo:latest
  ports:
    - "3200:3200"
    - "9411:9411"
  command: [ "-config.file=/etc/tempo.yaml" ]
  volumes:
    - ./tempo.yaml:/etc/tempo.yaml

grafana:
  environment:
    - GF_FEATURE_TOGGLES_ENABLE=traceqlEditor
  volumes:
    - ./grafana/datasources:/etc/grafana/provisioning/datasources
```

### 각 서비스 build.gradle

```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
implementation 'io.zipkin.reporter2:zipkin-reporter-brave'
```

### application.yml

```yaml
management:
  tracing:
    sampling:
      probability: 1.0   # 개발: 100%, 운영: 0.01
  zipkin:
    tracing:
      endpoint: http://tempo:9411/api/v2/spans
```

### Grafana 데이터소스 (tempo.yaml)

```yaml
apiVersion: 1
datasources:
  - name: Tempo
    type: tempo
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: prometheus
```

---

## 자동 계측 범위

별도 코드 수정 없이 자동으로 span이 생성된다.

| 구간 | 자동 여부 |
|------|----------|
| HTTP 요청/응답 | 자동 |
| RestTemplate 호출 | 자동 |
| JPA/JDBC 쿼리 | 자동 |
| Kafka 발행/소비 | `observation-enabled: true` 설정 필요 |

---

## Grafana 연동 — 메트릭에서 트레이스로 드릴다운

```
Grafana 대시보드
  hikariCP pending spike 확인
    ↓ 해당 시점 클릭
  Tempo 트레이스 목록 자동 연결
    ↓ 느린 요청 선택
  waterfall 조회
    ↓
  PG 호출 300ms 확인
```

메트릭과 트레이스를 별도로 확인하지 않고 Grafana 한 화면에서 이어진다.

---

## 결과 — 문제 2로 연결

Tempo waterfall로 병목 구간(PG 호출 300ms)을 특정한 뒤,
Grafana hikariCP 지표와 연결하여 원인을 진단했다.

이를 바탕으로 `@Transactional` 범위를 DB 작업만으로 축소하는 개선을 진행했다.

→ [문제 2. 외부 API와 트랜잭션 분리](./02-connection-pool-test.md)