package com.hah.here.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 멱등 헤더 기반 Layer 1 처리.
 *
 * 호출 정책 (이중 저장소):
 *  - 정상 모드:
 *    - claim   = Redis only (1000TPS 가 도달하므로 DB 부담 회피)
 *    - complete = DB 우선 영속 + Redis SET best-effort
 *      → Phase B 통과한 ~10건만 도달이라 DB 부담 무시 가능. *Redis 장애 중에도 응답 재현 보장*.
 *    - release = Redis + DB 둘 다 정리 (정상 흐름 INFLIGHT 마커 제거)
 *
 *  - Redis 장애 (Circuit Breaker OPEN):
 *    - claim   = DB 만 (IdempotencyDbStore.claim) — 동시 두 요청 race 는 PK UNIQUE 충돌로 직렬화
 *    - complete = DB 만 (이미 정상 모드에서 DB 우선이라 변경 없음)
 *    - release = DB 만
 *
 * 잃지 않는 것: Redis 장애 중에도 cached response UX 보장 (단, 정상→장애 *전환 직전* 의 short window 는 예외)
 * 잃지 않는 것: 중복 예약/중복 결제 — Layer 3 (reservation.idempotency_key UNIQUE) 가 끝까지 막음
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String CB_NAME = "idempotencyRedis";
    private static final String KEY_PREFIX = "idem:";
    private static final String IN_FLIGHT_MARKER = "__INFLIGHT__";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyDbStore dbStore;

    public String resolveKey(String headerKey, Long userId, Long productId) {
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }
        return "auto:" + userId + ":" + productId;
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "claimFallback")
    public IdempotencyOutcome claim(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        Boolean isFirst = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, IN_FLIGHT_MARKER, TTL);

        if (Boolean.TRUE.equals(isFirst)) {
            return IdempotencyOutcome.first();
        }

        String existing = redisTemplate.opsForValue().get(redisKey);
        if (IN_FLIGHT_MARKER.equals(existing)) {
            return IdempotencyOutcome.inFlight();
        }
        return IdempotencyOutcome.cached(existing);
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "completeFallback")
    public void complete(String idempotencyKey, Object response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("멱등 응답 직렬화 실패. 마커 제거.", e);
            release(idempotencyKey);
            return;
        }

        // DB 우선 영속 (Redis 장애 중에도 cached 응답 재현 가능)
        dbStore.complete(idempotencyKey, json);

        // Redis 캐시 (best-effort — 실패해도 DB 가 진실)
        redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, json, TTL);
    }

    /**
     * release 는 claim 과 동일한 비대칭 정책:
     *   정상 모드 = Redis only (거절 경로 1000TPS 도달 — DB 부담 회피)
     *   Redis 장애 (CB OPEN) = fallback → DB only
     *
     * Redis 정상 → 장애 *전환 직전* 의 short window 에 fallback claim 으로 DB INSERT 됐는데
     * release 시점에 Redis 회복돼 있으면 DB INFLIGHT 잔존 가능. sweeper 의 stale cleanup 으로 회수.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "releaseFallback")
    public void release(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        String value = redisTemplate.opsForValue().get(redisKey);
        if (IN_FLIGHT_MARKER.equals(value)) {
            redisTemplate.delete(redisKey);
        }
    }

    /** Redis 장애 시 DB 폴백 — DB 가 진실. */
    @SuppressWarnings("unused")
    private IdempotencyOutcome claimFallback(String idempotencyKey, Throwable t) {
        log.warn("Idempotency claim Redis 장애 — DB 폴백. cause={}", t.toString());
        return dbStore.claim(idempotencyKey);
    }

    @SuppressWarnings("unused")
    private void completeFallback(String idempotencyKey, Object response, Throwable t) {
        log.warn("Idempotency complete Redis 장애 — DB only. cause={}", t.toString());
        try {
            String json = objectMapper.writeValueAsString(response);
            dbStore.complete(idempotencyKey, json);
        } catch (JsonProcessingException e) {
            log.error("멱등 응답 직렬화 실패. 마커 제거.", e);
            dbStore.release(idempotencyKey);
        }
    }

    @SuppressWarnings("unused")
    private void releaseFallback(String idempotencyKey, Throwable t) {
        log.warn("Idempotency release Redis 장애 — DB only. cause={}", t.toString());
        dbStore.release(idempotencyKey);
    }
}
