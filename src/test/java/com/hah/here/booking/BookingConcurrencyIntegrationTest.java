package com.hah.here.booking;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import com.hah.here.payment.PaymentRepository;
import com.hah.here.payment.PaymentRequest;
import com.hah.here.payment.method.MethodType;
import com.hah.here.reservation.Reservation;
import com.hah.here.reservation.ReservationRepository;
import com.hah.here.stock.ProductStock;
import com.hah.here.stock.ProductStockRepository;
import com.hah.here.support.RedisTestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스펙 핵심 검증 — 1000 동시 booking → 정확히 10건만 CONFIRMED.
 *
 * 분산 환경(Phase A Lua atomic) 이 race condition 없이 정합성 보장하는지 *코드로 입증*.
 * 1u1p 와 idempotency 의 세부 검증은 RedisLuaCallerIntegrationTest /
 * IdempotencyServiceIntegrationTest 에서 별도로 다룸.
 */
@SpringBootTest
class BookingConcurrencyIntegrationTest extends RedisTestContainerSupport {

    private static final Long PRODUCT_ID = 1L;
    private static final int TOTAL_STOCK = 10;
    private static final int CONCURRENT_USERS = 1000;

    @Autowired private BookingService bookingService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductStockRepository productStockRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        // DB — 이전 테스트 영향 제거
        jdbcTemplate.execute("DELETE FROM payment");
        jdbcTemplate.execute("DELETE FROM reservation");
        jdbcTemplate.execute("DELETE FROM user_product_hold");
        jdbcTemplate.execute("DELETE FROM idempotency_record");
        jdbcTemplate.update("UPDATE product_stock SET remaining = ? WHERE product_id = ?",
                TOTAL_STOCK, PRODUCT_ID);

        // Redis — stock 초기화 + entered 비움 + idem 캐시 비움
        redisTemplate.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(TOTAL_STOCK));
        redisTemplate.delete("entered:" + PRODUCT_ID);
        Set<String> idemKeys = redisTemplate.keys("idem:*");
        if (idemKeys != null && !idemKeys.isEmpty()) {
            redisTemplate.delete(idemKeys);
        }
    }

    @Test
    @DisplayName("1000 동시 booking → 정확히 10건만 CONFIRMED, 오버셀 0")
    void exactlyTenConfirmedUnderConcurrency() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final long userId = 10_000L + i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    BookingRequest req = new BookingRequest(
                            userId, PRODUCT_ID,
                            List.of(new PaymentRequest(MethodType.YPAY, new BigDecimal("50000")))
                    );
                    bookingService.book(req, UUID.randomUUID().toString());
                    successes.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.SOLD_OUT
                            || e.getErrorCode() == ErrorCode.ALREADY_RESERVED) {
                        rejections.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = finishGate.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("60초 안에 모든 요청 완료").isTrue();

        // 1) 응답 측 — 정확히 10건 성공
        assertThat(successes.get()).as("성공 응답 수").isEqualTo(TOTAL_STOCK);
        // 2) DB reservation — CONFIRMED 정확히 10건
        long confirmed = reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == Reservation.Status.CONFIRMED)
                .count();
        assertThat(confirmed).as("DB CONFIRMED reservation").isEqualTo(TOTAL_STOCK);
        // 3) DB payment — 10건
        assertThat(paymentRepository.count()).as("payment row").isEqualTo(TOTAL_STOCK);
        // 4) DB product_stock — 0
        ProductStock dbStock = productStockRepository.findById(PRODUCT_ID).orElseThrow();
        assertThat(dbStock.getRemaining()).as("DB product_stock.remaining").isEqualTo(0);
        // 5) Redis stock — 0
        assertThat(redisTemplate.opsForValue().get("stock:" + PRODUCT_ID))
                .as("Redis stock counter").isEqualTo("0");
        // 6) Redis entered ZSET — 정확히 10명
        Long zCard = redisTemplate.opsForZSet().zCard("entered:" + PRODUCT_ID);
        assertThat(zCard).as("Redis entered ZSET 멤버 수").isEqualTo((long) TOTAL_STOCK);
    }
}
