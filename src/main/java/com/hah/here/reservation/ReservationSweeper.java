package com.hah.here.reservation;

import com.hah.here.payment.Payment;
import com.hah.here.payment.PaymentOrchestrator;
import com.hah.here.payment.PaymentRepository;
import com.hah.here.stock.RedisStockGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PENDING reservation 회수 sweeper.
 *
 * 결제 도중 앱 서버 OOM 등으로 동기 보상이 못 돈 경우 5분+ 지난 PENDING 을 회수한다.
 * 순서: payment 환불 → reservation.fail (stock + hold 자동 cleanup) → Redis entered: 해제.
 *
 * 트랜잭션은 호출 대상 service 메서드(reservationService.fail, paymentOrchestrator.refundAll 등)
 * 가 자체 관리하므로 sweeper 메서드 자체에는 @Transactional 불필요.
 *
 * 분산 환경 멀티 인스턴스 동시 sweep 안전성:
 *  - reservationService.fail() — 비관적 락 + wasPending 검사로 stock 복구 한 번만
 *  - paymentOrchestrator.refundAll() — SUCCESS→REFUNDING 조건부 transition 으로 PG 환불 권한 분배
 *  - release_seat.lua — ZSCORE 검증 후에만 ZREM/INCR (idempotent)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationSweeper {

    private static final long SWEEP_INTERVAL_MS = 60_000L;        // 1분
    private static final long INITIAL_DELAY_MS = 60_000L;
    private static final int PENDING_GRACE_MINUTES = 5;

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final PaymentRepository paymentRepository;
    private final PaymentOrchestrator paymentOrchestrator;
    private final RedisStockGate redisStockGate;

    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = SWEEP_INTERVAL_MS)
    public void sweepStalePending() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(PENDING_GRACE_MINUTES);
        List<Reservation> stale;
        try {
            stale = reservationRepository.findPendingOlderThan(threshold);
        } catch (Exception e) {
            log.error("PENDING 조회 실패. 다음 주기 재시도", e);
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        log.info("PENDING 회수 시작. {}건", stale.size());

        for (Reservation r : stale) {
            try {
                // 1) 먼저 fail() 로 PENDING→FAILED 천이 시도 (비관적 락 + 상태 검사).
                //    이미 CONFIRMED 인 경우 Reservation.fail() 이 IllegalStateException 던져 skip.
                //    즉 *방금 정상 confirm 된 reservation 의 결제를 잘못 환불하지 않는다*.
                Reservation failed;
                try {
                    failed = reservationService.fail(r.getId());
                } catch (IllegalStateException e) {
                    log.info("Sweeper skip — 이미 CONFIRMED 처리됨. reservationId={}", r.getId());
                    continue;
                }

                // 2) fail() 천이 성공한 경우만 환불 + Redis 자리 해제
                //    PENDING 도 포함: PG charge 후 markSuccess 실패한 잔존 결제(외부 자원에 결제됨)도 환불 대상.
                List<Payment> payments = paymentRepository.findByReservationId(failed.getId()).stream()
                        .filter(p -> p.getStatus() == Payment.Status.SUCCESS
                                || p.getStatus() == Payment.Status.PENDING)
                        .toList();
                if (!payments.isEmpty()) {
                    paymentOrchestrator.refundAll(failed.getUserId(), payments);
                }
                redisStockGate.release(failed.getUserId(), failed.getProductId());
                log.info("PENDING 회수 완료. reservationId={}", failed.getId());
            } catch (Exception e) {
                log.error("PENDING 회수 실패. reservationId={}, 다음 주기 재시도", r.getId(), e);
            }
        }
    }
}
