# AI 활용 기록

본 프로젝트의 설계와 구현 과정에서 LLM 기반 AI 도구(Claude)를 활용했습니다. 활용 범위와 본인의 판단/기여를 분리해 정리합니다.

---

## 사용한 도구

- Claude (Anthropic) — 설계 토론, 코드 초안, 문서 정리에 사용

---

## 활용한 부분

### 설계 단계

- 문제의 본질(*30만 요청 거절 비대칭 시스템*) 정의에 대한 사고 정리
- Phase A / Phase B 분리, Redis admission gate, 다층 fallback 등 핵심 패턴의 트레이드오프 비교 토론
- Saga 순서(내부 자원 → 외부 자원), 멱등성 3중 방어 layer 같은 패턴의 적용 타당성 검토

### 구현 단계

- 도메인별 패키지(`stock`, `payment`, `reservation` 등) 의 첫 코드 초안
- `reserve_seat.lua` / `release_seat.lua` Lua 스크립트의 KEY_MISSING 분기, ZSET grace time 도입 같은 디테일 보강
- Resilience4j Circuit Breaker / RateLimiter / Bulkhead 의 application.yaml 튜닝 값 합의
- 통합 테스트 시나리오 설계 (1000 동시 booking에서 정확히 10건 CONFIRMED, 보상 흐름 멱등 검증 등)

### 문서 단계

- README.md 의 시퀀스 다이어그램 / ERD / 운영 메모 구조 잡기
- DECISIONS.md 의 쟁점별 비교표와 판단 근거 정리
- 한국어 어투, 합쇼체 통일, 용어 일관성 점검 같은 정리 작업

---

## 본인이 직접 한 부분

- 모든 기술 선택의 **최종 결정** (예: ZSET 채택 vs 단순 Set, 자동 rebuild 미도입, 분산 락 미도입 등 비자명한 trade-off 판단)
- 코드 리뷰와 통합 — AI가 제시한 초안의 패키지 경계, 트랜잭션 경계, 실제 호출 흐름이 의도대로 동작하는지 직접 검증
- 부하 테스트(k6) 시나리오 작성과 실측, 결과 해석
- 도메인 모델링 결정 (`product` ↔ `product_stock` 분리, `user_product_hold` 도입 등)
- 문서의 어투/구조/생략 여부에 대한 편집 결정

---

## 활용에 대한 판단

AI를 *사고 파트너* 와 *드래프트 도구* 로 활용했습니다. 코드와 문서의 초안이 빠르게 나오는 만큼, 그 트레이드오프가 본인의 도메인 이해와 맞는지 검증하는 시간을 별도로 잡았습니다.

특히 다음 결정들은 AI 제안을 그대로 수용하지 않고 비교 후 의도적으로 다른 방향을 택했습니다.

- ZSET 도입 — 처음 단순 Set 으로 갔다가 sweeper grace time 표현 필요성으로 변경
- 분산 락 미도입 — sweeper가 idempotent하므로 잉여, Redis 분산 락은 자기 모순
- 자동 rebuild 미도입 — race 위험 vs fail-safe 단순성에서 후자

이러한 판단의 *왜* 가 결과물인 DECISIONS.md 의 본문이라고 보면 됩니다.
