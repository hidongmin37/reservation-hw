package com.hah.here.stock;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase A 의 raw Redis Lua 호출 + Circuit Breaker 적용.
 *
 * RedisStockGate 와 분리한 이유:
 *  - @CircuitBreaker AOP 는 *외부 호출* 에만 걸리므로(Spring proxy 한계),
 *    retry/fallback orchestration 을 같은 클래스에서 self-call 하면 무력화된다.
 *  - 이 빈은 raw 호출만 담당, RedisStockGate 가 KEY_MISSING/REDIS_DOWN 분기 처리.
 *
 * Circuit Breaker 가 OPEN 되면 fallback method 가 호출되어 REDIS_DOWN 을 반환한다.
 * 정상 응답 중 KEY_MISSING(키 유실, 연결은 정상) 은 *예외가 아니므로* CB 와 무관하게
 * 그대로 반환되어 호출자가 rebuild 트리거.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisLuaCaller {

    private static final String CB_NAME = "redisStockGate";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> reserveSeatScript;
    private final RedisScript<Long> releaseSeatScript;

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "reserveCircuitOpen")
    public ReservationResult reserve(String stockKey, String enteredKey, Long userId) {
        String reservedAt = String.valueOf(System.currentTimeMillis());
        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) redisTemplate.execute(
                reserveSeatScript, List.of(stockKey, enteredKey),
                String.valueOf(userId), reservedAt);
        return mapResult(result);
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "releaseCircuitOpen")
    public void release(String stockKey, String enteredKey, Long userId) {
        redisTemplate.execute(
                releaseSeatScript, List.of(stockKey, enteredKey), String.valueOf(userId));
    }

    @SuppressWarnings("unused")
    private ReservationResult reserveCircuitOpen(String stockKey, String enteredKey, Long userId, Throwable t) {
        log.warn("Redis reserve 실패(CB 신호). stockKey={}, cause={}", stockKey, t.toString());
        return ReservationResult.redisDown();
    }

    @SuppressWarnings("unused")
    private void releaseCircuitOpen(String stockKey, String enteredKey, Long userId, Throwable t) {
        log.warn("Redis release 실패(CB 신호). stockKey={}, cause={} — DB stock 복구는 reservation.fail 이 담당",
                stockKey, t.toString());
    }

    private ReservationResult mapResult(List<Long> result) {
        long status = result.get(0);
        if (status == 1L) {
            return ReservationResult.success(result.get(1).intValue());
        }
        if (status == -1L) {
            return ReservationResult.alreadyReserved();
        }
        if (status == -2L) {
            return ReservationResult.keyMissing();
        }
        return ReservationResult.soldOut();
    }
}
