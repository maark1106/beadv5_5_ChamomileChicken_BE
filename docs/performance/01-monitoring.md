

# 문제 1. 모니터링 환경 구축 + AOP를 활용하여 분산 환경에서 요청 흐름 추적

## 성능 분석 기준

요청 처리 흐름을 아래 6개 구간으로 나누어 관찰한다.

| 구간 | 확인 내용 | 주요 지표 |
|------|-----------|-----------|
| 요청 | 초당 요청량, 에러율 | `http_server_requests_seconds_count` |
| 스레드풀 | 요청 처리 스레드 점유 | `tomcat_threads_busy_threads` |
| 외부 API | 서비스 간 호출, PG 호출 지연 | AOP 실행 시간, TraceId 로그 |
| 커넥션 풀 | DB 커넥션 대기/고갈 | `hikaricp_connections_active`, `pending` |
| 쿼리 | DB 실행 시간, Full Scan 여부 | `EXPLAIN`, query execution time |
| 응답 | 최종 latency | p95, p99 latency |

---

## 도입 배경

MSA 환경에서는 하나의 요청이 여러 서비스를 거치기 때문에, 단순히 "응답이 느리다"만으로는 병목 위치를 알기 어렵다.

따라서 성능 개선 전에 먼저 요청 흐름을 아래 6개 구간으로 나누어 관찰할 수 있는 기반이 필요했다.

```
요청 → 스레드풀 → 외부 API → 커넥션 풀 → 쿼리 → 응답
```

이를 위해 두 가지를 도입했다.

```
Prometheus + Grafana
→ JVM, HTTP, CPU, HikariCP 등 정량 지표 수집

AOP + TraceId
→ 서비스 내부 메서드 실행 시간과 서비스 간 호출 흐름 추적
```

---

## 목표

성능 개선 전, 병목 위치를 감으로 추정하지 않고 **지표와 로그를 기반으로 특정할 수 있는 환경**을 만든다.

---

## 적용 내용

- Spring Boot Actuator + Micrometer 적용
- Prometheus + Grafana 구축
- 서비스별 `application` 태그 추가
- Grafana 대시보드 구성
- TraceId 기반 요청 추적 필터 적용
- AOP 기반 주요 메서드 실행 시간 로깅

---

## 검증 방법

```
k6 smoke test
VU 10 / 1분
```

---

## 확인할 것

- Prometheus Targets에서 spring-services UP 확인
- Grafana에서 서비스별 JVM, CPU, HTTP, HikariCP 지표 확인
- TraceId로 하나의 요청이 여러 서비스에서 연결되어 보이는지 확인
- AOP 로그로 주요 메서드 실행 시간이 기록되는지 확인

---
