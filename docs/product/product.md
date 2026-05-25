# Product 서비스 구조 및 흐름

## 배경 및 목적

`product` 서비스는 상품 도메인의 중간 서비스로, 상품 CRUD뿐 아니라 일정 관리, 예약 선점, 재고 차감/복구, 리뷰/찜, 검색 연동까지 함께 담당합니다.

현재 프로젝트에서 `product` 서비스는 아래 역할을 가집니다.

| 항목 | 역할 |
|------|------|
| 상품 관리 | 상품 등록, 수정, 삭제, 단건/목록 조회 |
| 일정 관리 | 상품별 일정 생성, 수정, 삭제, 단건/목록 조회 |
| 예약 관리 | 주문 전 재고 검증, 예약 사용자 생성 |
| 결제 후처리 | 결제 성공 시 예약 확정, 취소/환불 시 재고 복구 |
| 사용자 연동 | 판매자, 예약 사용자 이름 조회 |
| 파일 연동 | 상품 이미지 업로드 확인 |
| 검색 연동 | Elasticsearch 색인 이벤트 발행 및 조회 |
| 배치 처리 | 만료 일정 자동 마감 스케줄러 |

---

## 현재 구현 상태

**주요 내부 API**

| 영역 | 엔드포인트 | 설명 |
|------|------------------|------|
| 상품 | `GET /api/v1/products` | 상품 검색 목록 조회 |
| 상품 | `POST /api/v1/products` | 상품 생성 |
| 상품 | `PUT /api/v1/products/{productId}` | 상품 수정 |
| 상품 | `DELETE /api/v1/products/{productId}` | 상품 삭제 |
| 일정 | `POST /api/v1/products/{productId}/schedules` | 일정 생성 |
| 일정 | `POST /api/v1/products/schedules/{scheduleId}` | 예약 기준 일정 단건 조회 |
| 일정 | `POST /api/v1/products/reservations` | 주문 전 예약 검증 |
| 리뷰 | `POST /api/v1/products/{productId}/reviews` | 리뷰 생성 |
| 찜 | `POST /api/v1/products/schedules/{scheduleId}/favorites` | 찜 생성 |

**현재 서비스 흐름의 축**

```
Controller
  -> UseCase
    -> Application Service
      -> Domain Repository
      -> ACL Client (user, file)
      -> Event Publisher / Kafka
      -> Elasticsearch Adapter
      -> Scheduler
```

**현재 도메인 특징**

- 상품 생성/수정 시 파일 서비스와 사용자 서비스 조회가 함께 묶여 있습니다.
- 일정은 `capacity`를 통해 재고 역할을 수행합니다.
- 예약 엔티티는 `ProductUser`로 별도 관리합니다.
- 결제 이후 상태 변경은 이벤트 기반 후처리 성격이 강합니다.
- 사용자 식별값은 product 서비스가 직접 인증해서 얻는 것이 아니라 API Gateway가 주입한 `X-User-Id`, `X-User-Role` 헤더를 신뢰하는 구조입니다.
- 만료 일정은 스케줄러가 배치로 `CLOSED` 상태로 전환합니다.

---

## 사용자 식별 방식

현재 product 서비스는 자체 시큐리티 필터 체인보다 API Gateway가 전달한 헤더를 기준으로 요청 주체를 식별합니다.

| 헤더 | 의미 | 사용 위치 |
|------|------|-----------|
| `X-User-Id` | 현재 요청 사용자 ID | `@CurrentUser` |
| `X-User-Role` | 현재 요청 사용자 권한 | `@CurrentUserRole` |

동작 흐름:

```
API Gateway
  -> X-User-Id / X-User-Role 헤더 주입
  -> product 서비스
    -> CurrentUserArgumentResolver / CurrentUserRoleArgumentResolver
    -> Controller 파라미터 바인딩
```

즉 product 서비스는 사용자 인증을 직접 수행하기보다 Gateway가 검증 후 전달한 사용자 식별값을 신뢰하는 방식으로 동작합니다.

---

## 주요 도메인 구조

### 엔티티별 역할

| 엔티티 | 설명 | 핵심 필드 |
|--------|------|-----------|
| `Product` | 상품 기본 정보 | `sellerId`, `title`, `description`, `price`, `maxCapacity`, `status` |
| `Schedule` | 상품별 날짜/시간 정보 | `productId`, `scheduleDt`, `startTime`, `endTime`, `status`, `capacity` |
| `ProductUser` | 특정 일정에 대한 예약 사용자 | `productScheduleId`, `userId`, `guestCount`, `status`, `restoreStatus` |
| `Review` | 상품 리뷰 | `productId`, `userId`, `rating`, `content` |
| `Favorite` | 상품 찜 | `productId`, `userId` |

### 상태값

| 타입 | 값 |
|------|----|
| `ProductStatus` | `ENABLE`, `DISABLE` |
| `ReservedStatus` | `FULL`, `AVAILABLE`, `CLOSED` |
| `ReservationStatus` | `RESERVED`, `CONFIRMED`, `RELEASED`, `REFUNDED` |

> `ReservedStatus`는 일정(`Schedule`) 상태이고, `ReservationStatus`는 예약 사용자(`ProductUser`) 상태입니다. 기존 대기(`PENDING`) 상태는 제거되었습니다.

---

## 패키지 구조 및 계층 역할

```
presentation/controller/
  ProductRestController.java
  SchdulesRestController.java
  ReviewRestController.java
  FavoritesRestController.java
  ProductInternalController.java

application/service/
  ProductService.java
  ScheduleService.java
  ProductUserService.java
  ReviewService.java
  FavoriteService.java

application/scheduled/
  ScheduleStatusScheduler.java

domain/model/
  Product.java
  Schedule.java
  ProductUser.java
  Review.java
  Favorite.java

domain/repository/
  ProductRepository.java
  ScheduleRepository.java
  ProductUserRepository.java
  ReviewRepository.java
  FavoriteRepository.java
  ProductSearchRepository.java

infrastructure/acl/
  user, file 서비스 호출 클라이언트
infrastructure/persistence/
  JPA Repository Adapter

infrastructure/kafka/
  주문/결제 이벤트 소비
  ES 색인 이벤트 발행/소비

infrastructure/elasticsearch/
  ProductDocument.java
  ProductSearchRepositoryAdapter.java
```

---

## 도메인 서비스별 책임

### ProductService

| 기능 | 설명 |
|------|------|
| 상품 생성 | 이미지 확인, 상품 저장, 판매자 조회, ES 저장 이벤트 발행 |
| 상품 수정 | 소유권 검증, 필드 수정, 이미지 재확인, ES 저장 이벤트 발행 |
| 상품 삭제 | soft delete 처리, ES 삭제 이벤트 발행 |
| 상품 목록 조회 | MySQL 최신순 조회 + Redis multiGet으로 카운터 배치 조합 |
| 상품 단건 조회 | DB(정적 정보) + Redis(좋아요 수, 조회수) 조합 반환, 조회수 INCR |
| 카테고리/지역 필터 조회 | MySQL 필터 조회 + Redis multiGet 카운터 조합 |
| 정산용 조회 | product id 목록으로 상품 정보 반환 |
| ES 마이그레이션 | DB 데이터를 ES로 재색인 |

> 상품 조회 응답은 정적 데이터(DB)와 동적 카운터(Redis)를 애플리케이션에서 조합해서 반환한다. 자세한 내용은 [redis-counter.md](redis-counter.md) 참고.

### ScheduleService

| 기능 | 설명 |
|------|------|
| 일정 생성 | 날짜/시간 검증, 충돌 검증, 초기 재고 세팅 |
| 일정 수정 | 시간 충돌 재검증, 일정 상태/재고 변경 |
| 일정 삭제 | 일정 상태 종료 및 soft delete |
| 일정 목록 조회 | 상품 기준 일정 목록 조회 |
| 일정 단건 조회 | `productUserId` 기준으로 예약 대상 일정 조회 |
| 예약 검증 | 주문 전 재고 차감과 `ProductUser` 생성 |
| 예약 확정 | 결제 성공 시 `RESERVED -> CONFIRMED` |
| 재고 복구 | 취소/환불 시 재고 복구 및 상태 변경 |
| 잔여 좌석 조회 | 상품 최대 인원과 현재 재고를 조합해 반환 |
| 만료 일정 마감 배치 | 오늘 이전 일정 중 `AVAILABLE`, `FULL` 상태 일정을 배치로 `CLOSED` 처리 |

### ProductUserService

| 기능 | 설명 |
|------|------|
| 예약 사용자 생성 | 예약 사용자 엔티티 생성 |
| 예약 사용자 목록 조회 | user 서비스 이름 조회 후 응답 DTO 조합 |
| 내부 조회 | 예약 후속 처리용 엔티티 반환 |

### ReviewService

| 기능 | 설명 |
|------|------|
| 리뷰 생성 | 상품 리뷰 저장 |
| 리뷰 수정 | 본인 리뷰 검증 후 수정 |
| 리뷰 삭제 | soft delete |
| 사용자/상품별 조회 | delete 되지 않은 리뷰만 조회 |

### FavoriteService

| 기능 | 설명 |
|------|------|
| 찜 생성 | 상품 존재 확인, 중복 찜 방지, DB 저장, Redis like_count INCR |
| 찜 삭제 | 본인 찜 검증 후 soft delete, Redis like_count DECR |
| 사용자별 찜 조회 | 활성 찜 목록 반환 (상품 단위) |
| 좋아요 수 조회 | Redis 우선 조회, miss 시 DB COUNT(*) + SETNX 캐싱 |

> 찜은 기존 스케줄 단위에서 **상품 단위**로 변경되었다. 마이페이지에서 찜한 상품 목록 확인 후 결제 시 스케줄을 다시 선택하는 흐름이다.

---

## 주요 요청 흐름

### 상품 생성 흐름

```
ProductRestController
  -> ProductService.create()
    -> FileConfirmClient.confirmBulk()         // 이미지 확인
    -> ProductRepository.save()
    -> SellerApiClient.findSeller()            // 판매자 이름 조회
    -> ProductEsSaveEvent 발행
    -> ProductEventResponseDto 반환
```

### 상품 수정 흐름

```
ProductRestController
  -> ProductService.update()
    -> 상품 존재 여부 확인
    -> 판매자 소유 여부 검증
    -> 필드 수정
    -> 이미지가 있으면 file 서비스 재확인
    -> ProductEsSaveEvent 발행
```

### 일정 생성 흐름

```
SchdulesRestController
  -> ScheduleService.create()
    -> 상품 조회
    -> 판매자 소유 여부 검증
    -> 날짜/시간 검증
    -> 충돌 일정 조회
    -> Schedule(capacity = product.maxCapacity) 생성
    -> 저장
```

### 일정 단건 조회 흐름

```
SchdulesRestController
  -> ScheduleService.selectSchedules()
    -> ProductUser 조회
    -> ProductUser.productScheduleId로 Schedule 조회
    -> SchedulesResponseDto 반환
```

### 주문 전 예약 검증 흐름

```
내부 주문 요청
  -> ScheduleService.verification()
    -> Schedule 조회
    -> Product 조회
    -> 가격 검증
    -> scheduleRepository.verification()       // 재고 차감
    -> ProductUserService.create()             // 예약 사용자 생성
    -> OrderResponseDto 반환
```

### 결제 성공 후 예약 확정 흐름

```
결제 완료 이벤트
  -> ScheduleService.reservationCompleted()
    -> ProductUser 조회
    -> Schedule 존재 확인
    -> status RESERVED -> CONFIRMED 변경
```

### 결제 취소/환불 후 재고 복구 흐름

```
취소/환불 이벤트
  -> ScheduleService.restoringInventory()
    -> claimRestore()로 복구 대상 선점
    -> ProductUser / Schedule / Product 조회
    -> restoreCapacity()                       // 재고 복구
    -> updateStatus(RELEASED or REFUNDED)     // 최종 상태 변경
    -> 중간 실패 시 예외 발생
    -> 트랜잭션 전체 롤백
```

도메인 포인트:

- `claimRestore()`는 현재 복구 가능한 상태(`RESERVED`, `CONFIRMED`)인지와 중복 복구 여부를 함께 확인합니다.
- `restoreCapacity()` 또는 `updateStatus()`가 실패하면 예외를 던져 전체 트랜잭션을 롤백합니다.
- 따라서 재고만 복구되고 상태는 그대로인 채 커밋되는 상황을 방지합니다.

### 만료 일정 자동 마감 흐름

```
ScheduleStatusScheduler.closeExpiredSchedules()
  -> ScheduleService.closeExpiredSchedulesOnce()
    -> findClosableIds(today, CLOSED 제외)
    -> bulkClose(AVAILABLE, FULL -> CLOSED)
    -> 배치 크기만큼 반복 처리
```

### 리뷰 / 찜 흐름

```
ReviewRestController
  -> ReviewService
    -> 등록 / 수정 / soft delete / 목록 조회

FavoritesRestController
  -> FavoriteService
    -> 등록 / soft delete / 사용자별 목록 조회
```

---

## 외부 연동 구조

### user 서비스 연동

| 용도 | 사용 위치 |
|------|-----------|
| 판매자 이름 조회 | 상품 생성, 수정, 단건 조회 |
| 사용자 이름 일괄 조회 | 예약 사용자 목록 조회 |
| seller 정보 포함 | ES 문서 생성 시 사용 |

### file 서비스 연동

| 용도 | 사용 위치 |
|------|-----------|
| 업로드된 이미지 확인 | 상품 생성, 상품 수정 |

### Kafka / 이벤트 연동

| 용도 | 설명 |
|------|------|
| ES 색인 이벤트 | 상품 생성/수정/삭제 시 ES 반영 |
| 주문/결제 이벤트 | 예약 확정 및 재고 복구 |

---

## 데이터 및 운영 주의사항

### 스키마 관련
- `products_schedule.capacity`는 재고 역할이므로 null이면 안 됩니다.
- `products_users.status`는 현재 `ReservationStatus`와 DB 체크 제약조건이 반드시 일치해야 합니다.
- `products_users.reg_dt`는 null이면 안 되고, 마이그레이션 시 백필이 필요합니다.
- product 도메인은 `delete_dt` 기반 soft delete를 사용합니다.

### 운영 관련
- Hibernate `ddl-auto: update`만으로는 운영 스키마 변경을 안전하게 처리하기 어렵습니다.
- 예약 상태값 변경이 있었다면 DB check constraint도 함께 변경해야 합니다.
- 일정 재고(`capacity`)는 상품 최대 인원(`maxCapacity`)과 다른 독립 값이므로 마이그레이션 시 구분해서 봐야 합니다.
- Gateway가 주입하는 사용자 헤더를 신뢰하는 구조이므로 product 서비스는 별도 인증 체인을 두지 않습니다.
- `ReservedStatus`에서 대기 상태가 제거되었으므로 일정 상태 전이와 배치 마감 로직은 `AVAILABLE`, `FULL`, `CLOSED` 기준으로 봐야 합니다.

---

## 구현 체크리스트

### 상품 도메인
- [x] 상품 생성/수정/삭제 기능 구현
- [x] 이미지 확인 연동 구현
- [x] 판매자 정보 조회 연동 구현
- [x] ES 저장/삭제 이벤트 발행 구현

### 일정/예약 도메인
- [x] 일정 생성/수정/삭제 기능 구현
- [x] 일정 단건/목록 조회 기능 구현
- [x] 시간 충돌 검증 구현
- [x] 주문 전 재고 검증 구현
- [x] 예약 사용자 생성 구현
- [x] 결제 완료 후 예약 확정 구현
- [x] 취소/환불 후 재고 복구 구현
- [x] 재고 복구 실패 시 예외 기반 롤백 처리 반영
- [x] 만료 일정 자동 마감 스케줄러 구현

### 부가 기능
- [x] 리뷰 생성/수정/삭제/조회 구현
- [x] 찜 생성/삭제/조회 구현 (상품 단위로 변경)
- [x] 상품 검색의 ES 연동 구현

### Redis 카운터
- [x] product 서비스 Redis 설정 추가
- [x] 좋아요 수 Redis 카운터 (INCR/DECR + miss 시 DB fallback)
- [x] 조회수 Redis 카운터 (INCR + SETNX 초기화)
- [x] 상품 목록 조회 시 Redis multiGet 배치 조회
- [x] 상품 응답에 likeCount, viewCount 포함
- [x] 좋아요 수 보정 스케줄러 (매일 새벽 3시 DB 기준 재동기화)
- [x] 조회수 DB 동기화 스케줄러 (매시 정각 Redis → DB)

### 운영 정비 포인트
- [ ] `products_users.status` 제약조건을 현재 enum 기준으로 정리
- [ ] `products_schedule.capacity` null 데이터 정리 및 not null 고정
- [ ] product 스키마 변경용 수동 migration 안정화
