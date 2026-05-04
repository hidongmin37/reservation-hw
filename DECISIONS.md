# 설계 의사결정 기록

설계하면서 했던 고민, 비교했던 대안, 그리고 지금의 선택으로 간 이유를 정리한 글입니다.

---

## 머리말. 이 문제를 어떻게 봤는가

처음에 요건을 받고 제일 먼저 든 생각은, 이 문제는 *재고 10개를 어떻게 잘 잡는가* 가 본질이 아니라는 것이었습니다.

> **5분간 약 30만 요청 중 10개만 성공시키는 비대칭 시스템.**
> 99.99%는 거절됩니다. 거절 경로가 무너지면 시스템 전체가 무너집니다.

따라서 설계의 중심 질문은 *"10개를 어떻게 잡을까"* 가 아니라 *"30만 요청을 어떻게 빠르게 거절할까"* 가 됩니다. 이 인식이 다음 결정들의 출발점입니다.

- 거절 경로(Phase A)는 Redis 단일 round-trip으로, 결제 경로(Phase B)는 DB와 외부 PG로 분리합니다.
- 두 경로의 SLA(p99 5ms vs 2s)와 자원(스레드, 락, 트랜잭션) 정책을 별개로 둡니다.
- 모든 실패 분기에 보상 경로를 미리 그려두고, 동기/비동기 reconciliation을 이중화합니다.

---

## 1. 선착순 진입을 무엇으로 막을까

한정 수량 10개에 1〜5분간 500〜1000 TPS가 몰립니다. 앱 서버가 2대 이상 분산이라 단일 프로세스 락(`synchronized`, `ReentrantLock`)으로는 정합성을 보장할 수 없습니다. 미달도 초과도 절대 안 되고, 모든 사용자가 동등한 기회를 가져야 합니다.

가장 먼저 따져본 것이 DB 락입니다. `SELECT FOR UPDATE` 면 정합성은 분명히 보장됩니다. 다만 1000 TPS가 핫 row 한 줄에 다 걸리면 DB가 먼저 죽습니다. 정합성을 지키려다 시스템이 무너지는 전형적인 패턴입니다. 낙관적 락(`@Version`)도 비슷합니다. 충돌 retry가 폭증하면 사실상 spin lock이 되고 공정성이 무너집니다.

결국 결론은 Redis를 admission control gate로 두고, 카운터와 ZSET을 Lua로 atomic하게 처리하는 방향입니다. DB는 영속 source of truth로 두고, Redis는 실시간 진입 허용/거절만 담당합니다.

키 구조는 단순합니다.

- `stock:{productId}` : String counter (DECR/INCR/GET)
- `entered:{productId}` : ZSET (member=userId, score=reservedAt millis)

Lua 스크립트(`reserve_seat.lua`)가 ZSCORE → GET → DECR → ZADD를 단일 호출로 실행합니다.

```lua
if redis.call('ZSCORE', KEYS[2], ARGV[1]) ~= false then
  return {-1}                                       -- ALREADY_RESERVED
end
local stockRaw = redis.call('GET', KEYS[1])
if stockRaw == false then
  return {-2}                                       -- KEY_MISSING (호출자가 fallback 우회)
end
local stock = tonumber(stockRaw)
if not stock or stock <= 0 then
  return {0}                                        -- SOLD_OUT
end
redis.call('DECR', KEYS[1])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])      -- ARGV[2] = currentTimeMillis (sweep grace 용)
return {1, stock - 1}                              -- SUCCESS, remaining
```

여기서 한 가지 따로 신경 쓴 부분이 KEY_MISSING 분기입니다. `GET` 이 nil인 경우(키 유실)와 진짜 매진(`stock <= 0`)을 구별해야 합니다. 둘을 같이 SOLD_OUT으로 묶으면 Redis 재시작으로 키만 사라진 상황(연결은 정상이라 CB도 안 탐)에서 모든 요청이 거절되고 자동 복구가 없습니다. -2를 따로 두어 호출자(`RedisStockGate`)가 인지하고 fallback으로 우회하게 했습니다. 자동 rebuild는 race 위험이 있어 의도적으로 넣지 않았습니다. 자세한 내용은 4번에서 다룹니다.

### 비교했던 대안들

| 방안 | 정합성 | TPS 처리 | 단점 |
|---|---|---|---|
| DB `SELECT FOR UPDATE` | ✓ | 약함 | 1000 TPS 핫 row 락 대기열 폭주 |
| DB 낙관적 락 (`@Version`) | ✓ | 중간 | 충돌 retry 폭증, 사실상 spin lock |
| Redis `INCR`/`DECR` 단독 | ✓ | 강함 | 1유저-1상품 차단 불가 |
| Redis Set + Lua | ✓ | 강함 | sweep grace time 표현 불가, 정상 in-flight 자리 false-orphan 위험 |
| **Redis Counter + ZSET + Lua (채택)** | ✓ | 강함 | 단일 Redis 의존이라 fallback 별도 필요. score=reservedAt으로 sweep grace 가능 |
| Redis Stream (XADD) | ✓ | 강함 | 컨슈머 그룹 운영 복잡, 단순 진입 큐엔 과한 비용 |

채택 이유를 정리하면 다음과 같습니다.

1. 원자성. Lua는 Redis 단일 스레드에서 끊김 없이 실행되어 "확인 후 등록"의 race를 원천 제거합니다. DB 락보다 비용이 훨씬 낮습니다.
2. 단일 round-trip. 4개 명령을 한 번의 네트워크 호출로 처리합니다. 1000 TPS 환경에서 약 4000 명령/초 절약 효과가 있습니다.
3. 의도가 명확합니다. counter는 재고 카운트, set은 1유저-1상품 차단. 디버깅할 때 키 의미가 즉시 보입니다.
4. 단일 Redis 노드만으로도 commodity HW에서 100k+ ops/s 처리가 가능하므로 1000 TPS는 100배 헤드룸입니다. Scale-out 제한 가정에 부합합니다.

### 거절 경로를 더 가볍게

Phase A 자체가 sub-ms이긴 해도, 거절이 99.99%이므로 그 경로에서 떨궈낼 수 있는 비용은 모두 떨궈냈습니다.

- `ProductService` in-memory 캐시. 1000 TPS가 product 검증 시마다 DB read를 치지 않도록 ConcurrentHashMap read-through 패턴을 두었습니다. 첫 호출 후엔 sub-us hit입니다.
- 매진 negative cache. 매진 후 990 거절 요청이 Redis Lua를 매번 치는 비용을 제거합니다. `RedisStockGate.soldOutAt` ConcurrentHashMap, TTL은 1초로 짧게 잡았습니다. 결제 실패 보상으로 재고가 복구됐을 때 false-negative가 빠르게 풀려야 하기 때문입니다. 정합성의 진실은 여전히 Redis/DB이고, 캐시는 성능 최적화 전용입니다.
- 로그 레벨 분리. `SOLD_OUT` 이나 `ALREADY_RESERVED` 같은 정상 거절은 `ErrorCode.alarmWorthy=false` 로 분류해서 DEBUG 로그로 떨굽니다. application.yaml에서 `com.hah.here.common.exception: INFO` 로 끌어올려 부하 시 로그 폭증을 막습니다. WARN은 시스템 신호만 사용합니다.

순서는 `claim → product 검증 → Phase A` 인데, product 캐시 hit가 sub-us라 gate를 더 앞에 둘 실익이 없어 그대로 두었습니다.

### Bulkhead. checkout만, booking은 의도적으로 적용하지 않음

`CheckoutService.getCheckout` 에는 `@Bulkhead("checkout", maxConcurrentCalls=80, maxWaitDuration=50ms)` 를 걸었습니다. 한계 초과 시 `BulkheadFullException` 이 발생하여 `SERVICE_OVERLOADED (SYS_002, 503)` 로 응답합니다.

부하 측정에서 checkout p99가 booking burst와 겹치는 구간에 1.19s까지 폭증하는 것을 확인하고 넣은 것입니다. booking 트래픽이 checkout의 자원(Tomcat thread, DB/Redis 연결)을 완전히 점유하지 않게 격리하는 목적입니다.

booking 자체에는 bulkhead를 걸지 않았습니다. booking 전체에 bulkhead를 걸면 매진 요청까지 503으로 잘려나갑니다. 재고가 남아있는데도 시스템이 거절하는 모양이 되어 선착순 공정성이 깨집니다. 따라서 booking의 시스템 보호는 다음으로 분담합니다.

- Phase A (Redis admission gate)가 SOLD_OUT/ALREADY_RESERVED를 공정 거절(409)로 만듭니다.
- 시스템 보호는 RateLimiter(DbStockFallback)와 Tomcat accept-count 마지노선이 담당합니다.
- Phase B는 어차피 약 10건만 도달하므로 자원 격리가 불필요합니다.

### ZSET을 쓴 이유. score 사용처가 있었다

처음에는 score 사용처가 없으면 메타 빚이라는 판단으로 단순 Set을 검토했습니다. 그런데 Sweeper의 고아 자리 회수 grace time이 score 사용처로 자연스럽게 들어맞았습니다.

Phase A 통과 후 reservation INSERT 직전 사이의 정상 in-flight 자리를 sweeper가 false-orphan으로 오판하지 않으려면 각 entry의 등록 시각이 필요합니다. ZSET의 `score=reservedAt(ms)` 로 표현하면 sweeper가 `ZRANGEBYSCORE -inf NOW-5min` 으로 grace 지난 멤버만 검사할 수 있습니다. 정확히 score가 운영 정합성에 사용되는 케이스라 ZSET의 메타가 빚이 아니라 자산이 되었습니다.

### 핫키는 의도적으로 허용

`stock:{productId}` 단일 키에 모든 요청이 집중되는 것은 핫키 패턴입니다. 단일 상품의 재고 10을 여러 샤드(`:shard0`, `:shard1`)로 쪼개는 키 샤딩도 떠올려봤지만, 그 경우 다음 문제가 따라옵니다.

- 분산 합산 문제 (전체 잔여 = N개 샤드 합산 필요)
- 공정성 왜곡 (사용자 hash가 매진된 샤드로 라우팅되면 거절, 다른 샤드는 남음)
- atomic 보장이 깨짐

단일 Redis 노드의 처리 능력이 1000 TPS의 100배 이상이므로, 단일 키를 그대로 받아들였습니다.

상품 수가 1000개 이상으로 늘어나면 상품 단위 샤딩(productId 해시로 Redis Cluster slot 분배)을 검토하면 됩니다. 그것은 단일 상품의 재고를 분할하는 것과는 다른 문제이고, 정합성 측면에서 단일 키가 옳습니다.

### 공정성은 best-effort로 정의

공정성을 어떻게 정의할지가 사실 까다로운 부분입니다. Redis 도착 시점 FCFS를 공정성으로 정의했습니다. 클라이언트 네트워크 지연이나 LB 분배에 따른 차이는 통제 범위 밖으로 두고, 시스템적으로 특정 사용자를 우대하지 않으면 충분하다고 봤습니다.

Strict global ordering이나 lottery 방식도 떠올려봤는데, 동등한 확률 요구를 동등한 접근 기회로 해석해 채택하지 않았습니다. 추첨 방식은 선착순이라는 표현 자체에 어긋납니다.

---

## 2. 멱등성. 한 겹으로는 막지 못합니다

짧은 간격으로 들어오는 결제 요청을 중복 처리하면 안 됩니다. 원인은 다양합니다.

- 사용자 더블클릭 또는 새로고침
- 네트워크 retry (응답을 못 받아 재시도)
- 분산 환경에서 LB가 다른 서버로 재라우팅

처음에는 `Idempotency-Key` 헤더와 Redis SETNX 한 겹으로 막을 수 있을 것 같았지만, 단일 키 의존은 race window가 있습니다. SETNX 직후 응답 SET 사이에도 두 번째 요청이 도착할 수 있습니다. 한 layer만 두면 반드시 빠져나가는 케이스가 생깁니다.

따라서 3개 layer로 나눠 방어합니다.

| Layer | 메커니즘 | 막는 시나리오 |
|---|---|---|
| 1. 클라이언트 | `Idempotency-Key` 헤더 (UUID v4), Redis SETNX with INFLIGHT 마커 | 의도된 재시도 식별 (Stripe / RFC draft 표준) |
| 2. Redis | Phase A의 `entered:{productId}` Set, SISMEMBER | 같은 사용자의 동일 상품 재진입 (헤더 우회 포함) |
| 3. DB | `reservation.idempotency_key UNIQUE` 제약 | race window 잔존 시 최종 영속 안전망 |

상태 머신은 단순합니다.

- `claim()` : SETNX로 INFLIGHT 마커 등록. FIRST(첫 요청), IN_FLIGHT(처리 중 재요청), CACHED(응답 캐시 hit) 분기.
- `complete()` : 처리 완료 후 응답 JSON으로 마커 덮어쓰기.
- TTL 10분 (결제 사이클 + 재시도 윈도우).
- 헤더 누락 시 `auto:{userId}:{productId}` 자동 키 (1유저-1상품 보장과 일관).

각 layer의 책임이 다릅니다. Layer 1은 의도 식별(네트워크 재시도가 같은 요청인가), Layer 2는 상품 단위 1인 1자리(헤더로 우회해도 자리는 1개), Layer 3은 영속 단계의 최종 안전망(race가 잔존해도 DB가 거절). 세 가지 모두를 통과하는 race는 사실상 0이고, layer당 비용이 sub-ms라 부담은 미미합니다.

`Idempotency-Key` 를 서버 발급 토큰으로 두는 방법도 검토했는데, RFC draft(draft-ietf-httpapi-idempotency-key) 관행대로 클라이언트 발급이 일반적이라 그대로 따랐습니다. 헤더 fallback을 둔 이유는 시스템이 항상 멱등성을 보장해야 하기 때문입니다. 클라이언트가 헤더를 안 보내도 깨지면 안 됩니다.

### 이중 저장소. Redis와 DB

`IdempotencyService` 는 Redis(빠른 hit)와 DB(영속 진실) 두 저장소를 조합합니다. Redis 장애 중에도 응답 재현까지 보장하기 위함입니다.

| 단계 | 정상 모드 | Redis 장애 모드 (CB OPEN) |
|---|---|---|
| `claim` | Redis only. 1000 TPS 도달 부담 회피 | DB only. 동시 요청 race는 PK UNIQUE 충돌로 직렬화 |
| `complete` | DB 우선 영속, Redis SET best-effort | DB only |
| `release` | Redis와 DB 둘 다 INFLIGHT 마커 정리 | DB only |

Phase B 통과한 약 10건만 `complete` 에 도달하므로 정상 흐름의 DB write 부담은 무시 가능합니다. Redis 장애 발생 시점 이전에 완료된 요청은 DB에 영속되어 있어 fallback claim이 cached response로 응답을 재현합니다.

다만 잔여 한계가 있습니다. Redis 정상에서 장애로 전환되는 직전의 short window에 INFLIGHT 마커가 Redis에만 있던 요청은 DB에 흔적이 없어서, 같은 idem-key 재요청 시 cached response를 줄 수 없습니다. `reservation.idempotency_key UNIQUE` 가 중복 예약은 막지만 클라이언트는 `DUPLICATE_REQUEST` 에러를 받습니다. 모든 claim마다 DB write를 하면 막을 수 있는데, 1000 TPS 부담 대비 효과가 미미해 트레이드오프로 받아들였습니다.

`IdempotencyDbStore` 를 별도 빈으로 분리한 것은 `@Transactional` AOP가 fallback 흐름에서도 정상 작동하게 하기 위함입니다. service-layer 트랜잭션 관리 원칙을 그대로 따랐습니다.

---

## 3. 결제 수단 확장성. Booking 코드 0줄 수정이 목표

요건은 명확했습니다.

- 결제 수단: 신용카드, Y페이, Y포인트
- 복합결제: (신용카드 + 포인트) 또는 (Y페이 + 포인트). 신용카드+Y페이는 불가
- 신규 결제 수단 추가 시 Booking API의 비즈니스 로직 수정을 최소화 (OCP)

처음에 머릿속에 떠오른 것은 if/else 분기인데, 신규 수단이 추가될 때마다 Booking 코드를 건드려야 하는 것이 OCP 위반입니다. 따라서 Strategy 패턴, 조합 정책의 데이터화, Saga 오케스트레이터 조합으로 갔습니다.

```
PaymentMethod (Strategy 인터페이스)
  ├── CardPayment      → PaymentGateway.charge()
  ├── YPayPayment      → PaymentGateway.charge()
  └── PointPayment     → UserPointService.deduct()

PaymentComposition.ALLOWED = Set<Set<MethodType>>
  ├── { CARD }, { YPAY }, { POINT }
  ├── { CARD, POINT }
  └── { YPAY, POINT }
  // CARD + YPAY 는 ALLOWED에 없으므로 자동 거절

PaymentOrchestrator
  ├── PaymentComposition.validate()
  ├── POINT 우선 정렬 (Saga 순서)
  ├── 각 결제 처리 (createPending → charge → markSuccess)
  └── 실패 시 역순 보상 (refundAll)
```

신규 결제 수단 추가는 다음 3단계로 끝납니다.

1. `MethodType` enum에 한 줄 추가
2. 구현체 1개 추가
3. `ALLOWED` 에 허용 조합 추가

Booking 코드는 0줄입니다. `paymentOrchestrator.process(reservation, methods)` 만 호출하므로.

### 채택 근거

- OCP에 충실합니다. Booking 서비스는 어떤 결제 수단이 들어오든 알 필요가 없습니다.
- 조합 규칙이 데이터입니다. enum/Set으로 두면 신용카드+Y페이 같은 금지 조합도 코드 수정 없이 정책만 바꿔 차단할 수 있습니다.
- PG 연동을 추상화합니다. `PaymentGateway` 인터페이스와 Mock 구현체로 통신을 격리하여, 실제 PG 도입 시 인터페이스 구현체 1개만 추가하면 됩니다.
- Saga 순서. 내부 자원 먼저, 외부 자원 마지막. 이것이 핵심입니다.
  - POINT (DB 트랜잭션 내, 빠름, 롤백 쉬움) → CARD/YPAY (외부 PG, 느림, 환불 비동기·실패 가능)
  - 외부 자원 성공 후 내부 실패 시 PG 환불은 비동기·실패 가능하므로 시스템 일관성이 깨집니다. 롤백 가능성이 낮은 자원을 마지막에 배치하는 것이 Saga의 기본기입니다.
  - 보상 시에는 역순(외부 → 내부)으로 환불합니다.

---

## 4. Redis 장애. 단일 fallback은 함정입니다

설계 전체가 Redis Lua 게이트(`stock:{productId}` 와 `entered:{productId}`)에 의존합니다. Redis 마스터 다운이나 네트워크 장애 시 진입 자체가 막힙니다.

여기서 가장 경계한 것이 "Redis 죽으면 DB로" 라는 단순 fallback입니다. 1000 TPS가 DB 한 행에 SELECT FOR UPDATE 걸리면 DB까지 동반 사망합니다. 문제를 해결한 것이 아니라 옮긴 것뿐입니다.

따라서 다층 방어, 단일 fallback이 아닌 단계별 격리로 갔습니다.

```
[정상]      Redis (Sentinel HA)
  │ 장애 감지
  ↓
[1차]      Sentinel 자동 페일오버 (RTO ~30s)
  │ 페일오버 중 / 클러스터 전체 장애
  ↓
[2차]      Circuit Breaker (Resilience4j) OPEN
            → 503 + Retry-After header
  │ 평시 윈도우(~50TPS)에서만 활성화
  ↓
[3차]      DB Pessimistic Lock fallback
            (10개 row, 50TPS 락 경합 → 감당 가능)
```

피크 시간(약 1000 TPS) 윈도우에서는 DB Lock fallback을 비활성화합니다. 피크 시간에 DB로 트래픽을 옮기면 안 되기 때문입니다.

### 판단 근거

- 단일 fallback은 함정입니다. 1000 TPS가 DB 한 행에 걸리면 DB가 같이 죽습니다. 가용성을 추구하다 시스템 전체가 붕괴하는 것이 더 나쁩니다.
- 피크 시간엔 Fail Fast 우선입니다. Circuit Breaker로 빠르게 503을 반환하면 LB가 retry를 분산합니다. 시스템 보호가 일부 사용자 거절보다 우선입니다.
- 평시 50 TPS만 DB Lock 허용합니다. 행 락 경합이 가벼워 정합성 우선이 합리적입니다. 10개 row와 50 TPS는 InnoDB가 충분히 감당합니다.
- 트레이드오프는 명시합니다. 피크 시간대 일부 사용자는 거절될 수 있습니다. 그러나 전체 시스템 붕괴보다 일부 거절이 명백히 우선입니다.
- Fallback의 1유저-1상품 처리. Redis 장애 시 `entered:` Set 정보가 없어도 DB의 `reservation` 테이블 (status IN PENDING/CONFIRMED) 로 동일 사용자 active 예약 존재 여부를 검사합니다. `reservation.idempotency_key UNIQUE` (2번 layer 3) 가 최종 안전망입니다.

### 구현 매핑

- `RedisLuaCaller` (raw 호출 + `@CircuitBreaker`) 와 `RedisStockGate` (orchestration) 를 분리하여 self-call AOP 한계를 우회합니다.
- 흐름:
  1. `RedisLuaCaller.reserve()` : Lua 단일 round-trip
  2. KEY_MISSING (Lua `{-2}`) 또는 REDIS_DOWN (CB OPEN) : `DbStockFallback.reserve()` 우회 (fail-safe)
  3. `DbStockFallback` 의 `@RateLimiter` 가 50 TPS 초과 거절 : `BusinessException(REDIS_UNAVAILABLE)` → 503
- `DbStockFallback.reserve()` :
  - 비관적 쓰기 락과 writable TX. `product_stock` row 락 보유 상태에서 매진 검사
  - `user_product_hold` 테이블에 (user_id, product_id) UNIQUE INSERT 시도, `DataIntegrityViolation` 잡으면 `ALREADY_RESERVED`
  - 실제 stock 차감은 후속 흐름의 `ReservationService.create` 비관적 락에 위임
- 설정 (application.yaml):
  - `resilience4j.circuitbreaker.instances.redisStockGate` : sliding window 50, failure rate 50%, slow call 임계 완화, OPEN 10s, Lettuce/Redis 예외만 카운트
  - `resilience4j.ratelimiter.instances.dbStockFallback` : limitForPeriod 50, refresh 1s, timeoutDuration 0 (즉시 거절)

### 자동 rebuild를 일부러 적용하지 않음

KEY_MISSING 응답을 받았다고 요청 경로에서 `rebuildRedis(productId)` 를 자동 호출하면, 동시에 들어온 여러 요청이 각자 rebuild를 실행합니다. 그 결과 먼저 rebuild 후 DECR한 값을 늦은 rebuild가 DB remaining으로 덮어쓰는 race가 발생하여 초과 admission이 가능해집니다.

SETNX로 락을 걸어 보호하는 대안도 있는데, Redis 키 유실 자체가 비정상 상황이므로 fail-safe (모르면 fallback) 가 더 신뢰감 있는 정책이라고 봤습니다. Redis 키 정합 복원은 admin endpoint 또는 health-check에서 운영자 명시 트리거로만 수행합니다.

### 1u1p 최종 안전망. `user_product_hold` 테이블

정상 흐름은 Redis `entered:` ZSET이 1u1p를 보장합니다. 그런데 Fallback 모드에서 같은 사용자가 서로 다른 idempotency-key로 동시 진입하는 race는 비관적 락으로도 못 잡습니다. 락은 Phase A만 보호하고, Phase B의 reservation INSERT 사이 race window가 잔존합니다. 이를 막기 위해 `user_product_hold` 테이블에 `(user_id, product_id) UNIQUE` 제약을 두고 `DbStockFallback` 이 `saveAndFlush` 를 시도합니다. DB UNIQUE가 동시 두 INSERT 중 하나만 통과시킵니다.

Hold 라이프사이클은 다음과 같습니다. `DbStockFallback.reserve` 에서 INSERT, `ReservationService.confirm/fail` 에서 cleanup, 누수 시 sweeper의 `sweepStaleHolds` (1시간+)가 회수합니다.

운영 신호:

- Redis Sentinel 자동 승격 (RTO 30초 목표)
- Circuit Breaker open 시 알람 (Slack/PagerDuty)
- RateLimiter rejection rate (피크 시 fail-fast 작동 여부)

---

## 5. 결제 실패 보상과 시스템 장애 회복

진입(Redis) 성공 후 결제 실패(한도 초과, PG 응답 실패) 시 점유한 자리를 풀어야 합니다. 풀지 않으면 실질 매진 상태가 영원히 지속됩니다.

다만 동기 보상(catch + 즉시 처리)으로 잡지 못하는 케이스가 분명히 있습니다.

- 앱 서버 OOM 또는 강제 종료. catch 자체가 실행되지 않습니다.
- 보상 호출이 실패. 부분 회복 상태로 남습니다.

따라서 1차로 동기 보상(Lua atomic), 2차로 Sweeper 잡으로 최종 reconciliation을 하는 구조를 택했습니다.

동기 보상 Lua (`release_seat.lua`):

```lua
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
  return 0   -- 이미 해제됨 (idempotent no-op)
end
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[1])
return 1
```

비동기 Sweeper:

- 5분 이상 지난 PENDING reservation을 1분 주기로 검출
- `UPDATE reservation SET status='FAILED' WHERE status='PENDING' AND created_at < NOW() - INTERVAL 5 MINUTE`
- 영향 row마다 `release_seat.lua` 호출, 결제 환불, 포인트 환원
- 멱등성: WHERE status=PENDING 조건이 재실행 안전성 확보

### 판단 근거

- 결제 실패는 즉시 감지 가능합니다 (PG 응답 또는 timeout). 동기 보상이면 latency 영향이 미미하고 코드도 단순합니다.
- 장애로 동기 보상 자체가 실패하는 케이스(앱 서버 강제 종료, Redis 일시 장애)에 대비해 Sweeper가 최종 안전망 역할을 합니다.
- 누수에는 두 종류가 있습니다.
  - PENDING reservation 5분+ : Phase B 도중 사망. DB에 reservation row가 남아 있어 Sweeper가 회수합니다.
  - 고아 Redis `entered:` 엔트리 : Phase A 통과 후 reservation INSERT 직전 사망. DB에 흔적 없음. Sweeper가 별도로 Redis Set과 DB active reservation의 차집합을 정리합니다.
- Outbox 패턴도 검토했지만, 결제 실패가 즉시 감지되는 흐름엔 동기 보상과 Sweeper 조합으로 충분합니다. 이벤트 큐 운영 부담을 굳이 짊어질 이유가 없습니다.
- 결제 처리 전에 `userPoint.balance >= amount` 같은 사전 검증을 넣은 것은 UX 개선용입니다. 정합성의 진실은 PG 응답이고, 사전 검증은 빠른 거절을 위한 것입니다.

### 구현 매핑

- 동기 보상은 `BookingService.compensate()`. `paymentOrchestrator.refundAll` → `reservationService.fail` (또는 `releaseHold` if reservation==null) → `redisStockGate.release` → `idempotencyService.release` 순서로 실행합니다.
- 보상 가능 구간과 불가 구간을 분리합니다. `reservation.confirm()` 까지가 보상 가능 구간입니다. 그 이후 `idempotencyService.complete()` 의 실패는 별도 try-catch로 swallow합니다. 이미 결제와 예약이 사용자에게 약속된 상태에서 부수 작업 실패가 보상 흐름을 트리거하면 예약 살아있는데 결제 환불이라는 모순 상태가 발생하기 때문입니다. cached 응답 UX 손실은 수용합니다. 재요청은 `idempotency_key UNIQUE` 가 중복 booking을 차단합니다.
- Sweeper는 도메인별로 분리합니다.
  - `ReservationSweeper.sweepStalePending` : PENDING 5분+ 회수
  - `StockSweeper.sweepOrphanedSeats` : Redis ZSET 멤버 중 grace 5분 초과이고 DB active reservation 없는 자리 release (정상 in-flight 보호)
  - `StockSweeper.sweepStaleHolds` : `user_product_hold` 1시간+ 중 active reservation 없는 것 정리
- 멀티 인스턴스 동시 sweep은 안전합니다. 모든 단계가 idempotent입니다.
  - PG 환불: `PaymentRepository.tryAcquireRefund(id)` 의 SUCCESS→REFUNDING 조건부 UPDATE로 권한 분배. 영향 row 1인 인스턴스만 외부 PG `refund()` 호출.
  - stock 복구와 hold 정리: `ReservationService.fail()` 의 `findByIdForUpdate` 비관적 락과 `wasPending` 검사로 한 번만 실행.
  - Redis 자리 해제: `release_seat.lua` 가 ZSCORE 검증 후에만 ZREM/INCR 수행. 두 번째 호출은 no-op.
- 인덱스: `reservation (status, created_at)`. `Reservation` 엔티티의 `@Table(indexes = ...)` 와 `db/schema.sql` 양쪽에 명시.

### Stuck REFUNDING은 자동 재시도하지 않음. 수동 정산 한계 명시

`tryAcquireRefund` 통과 후 PG `refund()` 호출 도중 인스턴스가 죽으면 status=REFUNDING으로 잔존합니다. PG idempotency-key 보장이 없는 본 시스템에서 자동 재시도는 이중 환불 위험이 있어 의도적으로 수행하지 않습니다. 운영 환경에서는 PagerDuty/Slack 알림과 메트릭 카운터로 분리해서 운영자가 PG 콘솔로 실제 환불 여부 확인 후 수동 정산하는 흐름이 됩니다. 본 코드 베이스에는 알림 인프라가 미연동 상태라 모니터링 컴포넌트는 두지 않았습니다.

### 고아 자리 grace time의 의미

Phase A에서 reservation INSERT 사이의 정상 in-flight 자리는 보통 ms 단위에서 수백 ms 수준입니다. 5분 grace는 충분한 여유가 있어 false-orphan release 위험을 사실상 0으로 만듭니다. ZSET score=reservedAt 도입의 핵심 동기가 이것입니다.

### 분산 락 (ShedLock 등) 미도입 근거

@Scheduled 는 각 JVM 인스턴스에서 독립 실행되므로 N대 서버라면 sweep도 N번 발화합니다. 일반적으로 분산 락(`ShedLock`, Redisson 등)으로 1대만 실행하도록 강제하는 것이 정석이지만, 본 시스템에서는 의도적으로 도입하지 않았습니다.

| 항목 | 분석 |
|---|---|
| 모든 sweep 작업이 idempotent | PG 환불은 `tryAcquireRefund` (SUCCESS→REFUNDING 조건부 UPDATE) 로 권한 분배. 영향 row 1인 1대만 PG 호출. stock 복구와 hold cleanup은 `findByIdForUpdate` 비관적 락과 `wasPending` 검사로 1번만. Redis 자리 해제는 `release_seat.lua` 의 ZSCORE 검증으로 idempotent. DELETE WHERE는 InnoDB row-lock으로 직렬화. |
| 락 자체가 막는 것 | 두 인스턴스 동시 진입. 못 막는 것은 락 holder가 작업 중 죽으면 다음 holder가 절반 작업된 상태에서 들어온다는 점입니다. 결국 idempotency가 필요합니다. 즉 락이 있어도 idempotency가 없으면 안전하지 않습니다. 락은 최적화, idempotency가 정합성 수단입니다. |
| Redis 분산 락은 자기 모순 | sweeper의 핵심 회수 시나리오 중 하나가 Redis 장애로 누수된 자리 회수입니다. 락도 Redis라면 Redis 다운 시 sweeper 자체가 멈춥니다. 가장 필요한 순간에 못 돕니다. |
| DB 분산 락 (ShedLock-JDBC) 의 효과 vs 비용 | 효과는 select N번이 1번이 되는 것 (가벼운 인덱스 스캔이라 무시 가능). 비용은 의존성 +2, 락 테이블 +1, holder 사망 시 `lockUntil` 까지 다음 인스턴스 공백. 효과 대비 비용이 큽니다. |

결론적으로 본 sweeper는 idempotency로 정합성을 보장하므로 분산 락은 잉여입니다. 다른 도메인(예: 일별 정산 잡, 비싼 ML inference)에서 작업 자체가 idempotent하지 않은 경우엔 분산 락이 정답이지만, 본 책임 모델엔 맞지 않습니다.

---

## 6. 데이터 모델. 핫 row를 어떻게 격리할까

자정 트래픽 집중 시 재고 row(`product_stock`)가 hot row가 됩니다. 가격 수정이나 상품 정보 변경 같은 운영 작업이 자정 트래픽과 같은 트랜잭션 락에 묶이면 안 됩니다. 결제 수단별 row 추적, 멱등 안전망, 사용자 포인트 동시성 같은 모델링도 함께 정리해야 했습니다.

5개 테이블로 분리하여 각 테이블의 변경 cadence와 락 단위를 격리합니다.

```sql
product (id, name, description, price, check_in_at, check_out_at, total_stock, open_at)
product_stock (product_id PK, remaining)               -- 핫 row 격리
reservation (
  id, user_id, product_id, total_amount, status,
  idempotency_key UNIQUE,                              -- 멱등 layer 3
  created_at, updated_at
)
payment (id, reservation_id, method, amount, status, pg_transaction_id, created_at)
  -- status: PENDING / SUCCESS / FAILED / REFUNDING / REFUNDED
user_point (user_id PK, balance, version)              -- @Version 낙관적 락

user_product_hold (
  id PK, user_id, product_id, held_at,
  UNIQUE (user_id, product_id)                         -- DB Fallback 1u1p 최종 안전망
)
```

### 결정 근거

- `product` 와 `product_stock` 분리. 재고는 자정에 hot row가 됩니다. 다른 컬럼 변경(가격 수정 등 운영 작업)과 락 단위를 분리하면 트랜잭션 충돌이 줄어듭니다.
- `product.total_stock` 과 `product_stock.remaining` 의도적 분리.
  - `product.total_stock` : 원래 풀린 한정 수량 메타데이터. 변경이 거의 없습니다 (마케팅/리포트/감사용).
  - `product_stock.remaining` : 현재 잔여 핫 row. PENDING/CONFIRMED 차감 반영.
  - 운영 환경에선 `total_stock - remaining == count(active reservations)` 로 재고 drift 검증이 가능합니다. 본 범위에선 검증 로직 미구현. 운영 metric으로 분리합니다.
- `reservation.idempotency_key UNIQUE`. 멱등 보장의 최종 안전망 (2번 layer 3).
- `payment` 분리. 복합결제 표현, 결제 수단별 추적, 향후 결제 수단 추가 시 스키마 변경 불필요.
- `user_point.version` 낙관적 락. 사용자별 row 분산이고 1유저-1상품 강제로 동일 row 충돌이 시스템적으로 거의 없습니다. 낙관적이 자연스럽고, 충돌 발생은 외부 이상 신호이므로 retry 없이 fail이 옳습니다.
- Order 엔티티는 분리하지 않습니다. Reservation과 Payment로 충분합니다. e-commerce의 Order 추상화는 본 도메인(예약)엔 redundant입니다.
- `reservation (status, created_at)` 인덱스. Sweeper가 1분마다 `WHERE status='PENDING' AND created_at < threshold` 를 가벼운 스캔으로 처리합니다.

### Redis와 DB 재고 동기화 (영속 source of truth 보장)

Redis가 운영 진실(gate), DB가 영속 진실(`product_stock.remaining`)입니다. 두 상태가 drift하지 않도록 시점별로 정리하면 다음과 같습니다.

| 시점 | Redis | DB `product_stock.remaining` |
|---|---|---|
| Phase A 통과 (`redisStockGate.reserve`) | DECR + SADD entered | (그대로) |
| Phase B 진입 (`reservationService.create`) | (그대로) | 비관적 락 + `decrease()` (같은 TX에서 reservation INSERT) |
| Phase B 실패 (`reservationService.fail`) | release_seat.lua (compensate 흐름) | PENDING→FAILED 천이일 때만 `increase()` |
| Sweeper PENDING 회수 | release_seat.lua | reservationService.fail이 자동 처리 |
| Redis 키 소실 | `ProductStockService.rebuildRedis` | DB의 remaining으로 set, entered:는 active reservation 기준 재구성 |

이 모델에서 DB는 항상 점유 상태를 반영하는 진실입니다. Redis 유실 후 재초기화 시 초과판매 위험이 없습니다.

---

## 7. 결제 처리의 트랜잭션 경계

`PaymentOrchestrator` 가 처리하는 흐름은 다음과 같습니다.

1. Payment record INSERT (DB)
2. `PaymentMethod.charge()` (POINT는 DB UPDATE, CARD/YPAY는 외부 PG HTTP)
3. Payment record UPDATE (status=SUCCESS, pgTxId)

만약 (1)부터 (3)까지 전체를 단일 `@Transactional` 로 묶으면 외부 PG 응답 대기 동안 DB 커넥션이 점유됩니다. 1000 TPS 피크와 PG 응답 1〜3초가 만나면 HikariCP 풀이 폭발할 위험이 있습니다. InnoDB MVCC snapshot 누적, replication lag, row lock 보유 시간 연장 같은 부작용도 cascade합니다.

따라서 `PaymentOrchestrator` 에 `@Transactional` 을 부착하지 않습니다. DB 영속화는 별도 `PaymentPersistenceService` 로 분리하여 각 단계가 독립된 짧은 TX로 commit하게 했습니다.

```
TX1 [짧음]:  PaymentPersistenceService.createPending()  →  Payment INSERT, commit
(no TX):     PaymentMethod.charge()
              ├─ POINT       → UserPointService.deduct() (자체 @Transactional, 짧은 TX)
              └─ CARD/YPAY   → PaymentGateway.charge() (외부 HTTP, TX 없음)
TX2 [짧음]:  PaymentPersistenceService.markSuccess()    →  Payment UPDATE, commit
```

### 판단 근거

원칙은 단순합니다. 외부 IO는 절대 DB 트랜잭션 안에서 실행하지 않습니다. DB 커넥션은 한정 자원이기 때문입니다.

- DB 커넥션 점유 시간이 ms 수준입니다 (TX1, TX2 각각 짧게 commit). 외부 PG 응답이 느려도 커넥션 풀에 영향 없습니다.
- POINT의 `userPointService.deduct()` 도 같은 패턴이 자동 적용됩니다. `Propagation.REQUIRED` 기본값이라 부모 TX가 없으면 자체 새 TX를 생성하고 commit합니다. 부모 TX 제거가 핵심입니다.
- Strategy 패턴의 가치도 여기서 드러납니다. 호출자(`PaymentOrchestrator`)는 어떤 결제 수단인지 모르고도 트랜잭션 경계가 자연스럽게 격리됩니다.

### 잔여 위험과 대응

- TX1 commit 후 PG 호출 직전 앱 서버 죽음. 영원히 PENDING으로 남습니다.
- PG 성공 후 TX2 commit 실패. PG는 차감했는데 DB는 PENDING입니다.

Sweeper 잡(5번)이 5분+ PENDING을 검출해 reconciliation합니다. Eventually consistent 모델입니다. 강한 일관성을 약한 일관성과 보상 설계로 교환한 것이고, 시스템 부하 흡수 능력은 대폭 향상되었습니다.

---

## 기술 스택 선택 근거

언어와 프레임워크는 Java 21 + Spring Boot 3.5.14 를 채택했습니다. 요구 조건인 *Java 8 이상, Spring Boot 2.7 이상* 을 모두 충족하면서, Java 21 LTS의 virtual thread/record, Spring Framework 6 기반의 Jakarta 패키지와 JPA 3.x로 최신 표준에 정렬하기 위한 선택입니다. Spring Boot 2.7 대비 transitive 의존성 호환에 주의가 필요해 도입 라이브러리 버전은 BOM으로 고정합니다.

---

## 라이브러리 도입 사유

| 라이브러리 | 도입 사유 |
|---|---|
| Spring Boot Web / Validation | API 레이어와 요청 DTO 검증 표준 |
| Spring Data JPA + MySQL Connector | 영속화 표준, JPA로 도메인 응집 |
| Spring Data Redis (Lettuce) | Phase A admission gate, Lua 스크립트 실행 |
| Lombok (`@UtilityClass`, `@Builder` 등) | 보일러플레이트 감소, 의도 표현 강화 |
| Resilience4j | Circuit Breaker. Redis 장애 시 자동 OPEN으로 DB Fallback 호출 (4번) |
| Testcontainers (test) | MySQL/Redis 통합 테스트 환경 격리 |
