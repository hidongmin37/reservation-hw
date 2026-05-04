package com.hah.here.stock;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 장애 시 호출되는 DB 기반 fallback.
 *
 * 책임: Phase A 의 admission gate 역할만 대체.
 *  - 매진 빠른 거절: product_stock 비관적 락 + remaining 검사
 *  - 1유저-1상품 race 의 *최종* 안전망: user_product_hold UNIQUE 제약
 *
 * 실제 차감은 후속 흐름(ReservationService.create)의 비관적 락이 책임진다.
 * 사전 조회와 실제 차감 사이의 race 는 후자가 SOLD_OUT 으로 거절(정상 흐름과 동일).
 *
 * 한계:
 *  - Phase A 의 sub-ms 응답을 보장하지 못한다(DB 라운드트립 + 락 경합).
 *  - 따라서 평시 50TPS 윈도우에서만 합리적. 피크 트래픽에 호출되면 응답 지연 누적 가능 →
 *    @RateLimiter 50TPS + Tomcat accept-count 로 자체 보호.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DbStockFallback {

    private final ProductStockRepository productStockRepository;
    private final UserProductHoldRepository userProductHoldRepository;

    /**
     * 비관적 쓰기 락으로 같은 productId 의 fallback 호출을 직렬화한다.
     * 매진 검사 통과 후 user_product_hold INSERT 를 시도, UNIQUE 제약이 동시 두 요청 중
     * 하나만 통과시킨다 — *Phase A 락이 풀린 후*에도 1u1p 가 깨지지 않게 막는 최종 안전망.
     *
     * @RateLimiter: 평시 50TPS 만 허용. timeoutDuration=0 이라 초과 시 즉시 RequestNotPermitted.
     * 피크 1000TPS 가 fallback 으로 몰려 hot row 경합으로 DB 동반 사망하는 시나리오를 차단.
     * (DECISIONS.md 쟁점 4: "피크엔 fail-fast, 평시만 DB fallback")
     */
    @RateLimiter(name = "dbStockFallback")
    @Transactional
    public ReservationResult reserve(Long userId, Long productId) {
        ProductStock stock = productStockRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (stock.getRemaining() <= 0) {
            return ReservationResult.soldOut();
        }
        try {
            userProductHoldRepository.saveAndFlush(UserProductHold.builder()
                    .userId(userId)
                    .productId(productId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.info("DB Fallback: 1u1p hold UNIQUE 충돌 — 이미 active 예약 진행 중. userId={}, productId={}",
                    userId, productId);
            return ReservationResult.alreadyReserved();
        }
        log.info("DB Fallback admission. userId={}, productId={}, remaining={}",
                userId, productId, stock.getRemaining());
        return ReservationResult.success(stock.getRemaining());
    }

    /**
     * Fallback 모드의 release 는 별도 동작 불필요.
     * - DB stock 복구는 ReservationService.fail 트랜잭션 안에서 처리.
     * - hold 정리도 같은 트랜잭션에서 함께 진행.
     */
    public void release(Long userId, Long productId) {
        log.debug("DB Fallback release. userId={}, productId={} (no-op; reservation.fail 이 stock + hold cleanup)",
                userId, productId);
    }
}
