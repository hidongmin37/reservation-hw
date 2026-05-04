package com.hah.here.stock;

import com.hah.here.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Stock 도메인 자원 정리 sweeper.
 *
 * 두 가지 누수를 회수한다:
 *  1) Redis entered: ZSET 의 고아 멤버 — Phase A 통과 후 reservation INSERT 직전 사망 (grace 5분)
 *  2) Stale user_product_hold (1시간+) — fallback 흐름에서 hold INSERT 후 reservation 못 만들고 사망
 *
 * 각 sweep 메서드를 @Scheduled 로 직접 등록 — Spring scheduler 가 프록시를 통해 호출하므로
 * @Transactional AOP 가 정상 작동(self-call 우회).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockSweeper {

    private static final long SWEEP_INTERVAL_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 60_000L;
    private static final int STALE_HOLD_GRACE_MINUTES = 60;

    private final ProductRepository productRepository;
    private final ProductStockService productStockService;
    private final UserProductHoldRepository userProductHoldRepository;

    /**
     * Phase A 통과 후 reservation INSERT 직전 서버가 죽어 DB 에는 없고 Redis entered: 에만 남은 자리.
     * grace 5분 지난 ZSET entry 만 검사 (정상 in-flight false-positive 방지).
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = SWEEP_INTERVAL_MS)
    public void sweepOrphanedSeats() {
        productRepository.findAll().forEach(product -> {
            try {
                productStockService.cleanupOrphanedSeats(product.getId());
            } catch (Exception e) {
                log.error("고아 자리 회수 실패. productId={}, 다음 주기 재시도", product.getId(), e);
            }
        });
    }

    /**
     * Stale user_product_hold 정리.
     * fallback 흐름에서 hold INSERT 후 reservation 만들기 전에 죽은 경우.
     * 1시간+ 된 hold 중 active reservation 없는 것만 삭제.
     *
     * @Transactional: @Modifying delete 쿼리 실행을 위한 트랜잭션 경계.
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = SWEEP_INTERVAL_MS)
    @Transactional
    public void sweepStaleHolds() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_HOLD_GRACE_MINUTES);
        try {
            int deleted = userProductHoldRepository.deleteStaleOrphans(threshold);
            if (deleted > 0) {
                log.info("Stale hold 정리. count={}", deleted);
            }
        } catch (Exception e) {
            log.error("Stale hold 정리 실패. 다음 주기 재시도", e);
        }
    }
}
