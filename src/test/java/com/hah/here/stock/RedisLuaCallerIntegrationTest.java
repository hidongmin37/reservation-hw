package com.hah.here.stock;

import com.hah.here.support.RedisTestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A 의 Lua atomic 동작 검증.
 * Redis 컨테이너 위에서 reserve_seat.lua / release_seat.lua 의 모든 분기를 직접 호출.
 */
@SpringBootTest
class RedisLuaCallerIntegrationTest extends RedisTestContainerSupport {

    private static final Long PRODUCT_ID = 999L;
    private static final String STOCK_KEY = "stock:" + PRODUCT_ID;
    private static final String ENTERED_KEY = "entered:" + PRODUCT_ID;

    @Autowired
    private RedisLuaCaller luaCaller;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanRedisKeys() {
        redisTemplate.delete(STOCK_KEY);
        redisTemplate.delete(ENTERED_KEY);
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {

        @Test
        @DisplayName("정상 — stock 1, 첫 사용자 → SUCCESS, remaining 0")
        void firstUserSuccess() {
            redisTemplate.opsForValue().set(STOCK_KEY, "1");

            ReservationResult result = luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);

            assertThat(result.status()).isEqualTo(ReservationStatus.SUCCESS);
            assertThat(result.remaining()).isEqualTo(0);
            assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("0");
            assertThat(redisTemplate.opsForZSet().score(ENTERED_KEY, "1")).isNotNull();
        }

        @Test
        @DisplayName("매진 — stock 0 → SOLD_OUT")
        void soldOut() {
            redisTemplate.opsForValue().set(STOCK_KEY, "0");

            ReservationResult result = luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);

            assertThat(result.status()).isEqualTo(ReservationStatus.SOLD_OUT);
            assertThat(redisTemplate.opsForZSet().score(ENTERED_KEY, "1")).isNull();
        }

        @Test
        @DisplayName("키 유실 — stock 키 자체 없음 → KEY_MISSING (DB Fallback 트리거 신호)")
        void keyMissing() {
            // stock 키 set 안 함

            ReservationResult result = luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);

            assertThat(result.status()).isEqualTo(ReservationStatus.KEY_MISSING);
        }

        @Test
        @DisplayName("1u1p — 같은 사용자 두 번 시도 → 두 번째 ALREADY_RESERVED")
        void alreadyReserved() {
            redisTemplate.opsForValue().set(STOCK_KEY, "10");

            ReservationResult first = luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);
            ReservationResult second = luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);

            assertThat(first.status()).isEqualTo(ReservationStatus.SUCCESS);
            assertThat(second.status()).isEqualTo(ReservationStatus.ALREADY_RESERVED);
            // 한 번만 차감 — 정합성 검증
            assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("9");
        }

        @Test
        @DisplayName("ZSET score 가 reservedAt timestamp — sweep grace 검증 가능")
        void scoreIsTimestamp() {
            redisTemplate.opsForValue().set(STOCK_KEY, "10");
            long before = System.currentTimeMillis();

            luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);

            long after = System.currentTimeMillis();
            Double score = redisTemplate.opsForZSet().score(ENTERED_KEY, "1");
            assertThat(score).isNotNull();
            assertThat(score.longValue()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("정상 — 점유한 사용자 해제 → ZREM + INCR")
        void releaseEntered() {
            redisTemplate.opsForValue().set(STOCK_KEY, "10");
            luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);
            // stock 9, ZSET 에 user 1

            luaCaller.release(STOCK_KEY, ENTERED_KEY, 1L);

            assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("10");
            assertThat(redisTemplate.opsForZSet().score(ENTERED_KEY, "1")).isNull();
        }

        @Test
        @DisplayName("멱등 — 점유 안 한 사용자 release 호출 → no-op (정합성 보호)")
        void releaseIdempotent() {
            redisTemplate.opsForValue().set(STOCK_KEY, "10");

            luaCaller.release(STOCK_KEY, ENTERED_KEY, 999L);

            // stock 변하지 않음 — INCR 안 됨
            assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("10");
        }

        @Test
        @DisplayName("멀티 인스턴스 sweep race — 같은 사용자 두 번 release 해도 stock 한 번만 +1")
        void doubleReleaseSafe() {
            redisTemplate.opsForValue().set(STOCK_KEY, "10");
            luaCaller.reserve(STOCK_KEY, ENTERED_KEY, 1L);
            // stock 9

            luaCaller.release(STOCK_KEY, ENTERED_KEY, 1L);
            luaCaller.release(STOCK_KEY, ENTERED_KEY, 1L); // 두 번째 호출

            // stock 10 — INCR 한 번만
            assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("10");
        }
    }
}
