package com.hah.here.reservation;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import com.hah.here.stock.ProductStockService;
import com.hah.here.stock.UserProductHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ProductStockService productStockService;
    private final UserProductHoldRepository userProductHoldRepository;

    /**
     * Phase B 진입 시점에 reservation INSERT 와 product_stock 차감을 동일 트랜잭션으로 묶는다.
     * Redis 와 DB 가 같은 시점에 점유 상태를 반영하므로 drift 가 없다.
     */
    @Transactional
    public Reservation create(Long userId, Long productId, BigDecimal totalAmount, String idempotencyKey) {
        productStockService.decrease(productId);

        Reservation reservation = Reservation.builder()
                .userId(userId)
                .productId(productId)
                .totalAmount(totalAmount)
                .idempotencyKey(idempotencyKey)
                .build();
        try {
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        }
    }

    /**
     * 결제 완료 마킹. fallback 모드에서 만들어진 user_product_hold 가 있으면 함께 정리.
     * (정상 모드에서는 hold 가 없어 deleteByPK 가 no-op).
     *
     * 비관적 락 사용 이유: Sweeper 의 fail() 과 직렬화. confirm 이 락 없이 stale snapshot 으로
     * 진행되면 Sweeper 가 PENDING→FAILED 로 commit 한 row 를 다시 CONFIRMED 로 덮어쓰는 race 발생.
     * Reservation.confirm() 의 status==PENDING 검증과 결합되어 *둘 중 하나만* 통과한다.
     */
    @Transactional
    public Reservation confirm(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        reservation.confirm();
        userProductHoldRepository.deleteByUserIdAndProductId(
                reservation.getUserId(), reservation.getProductId());
        return reservation;
    }

    /**
     * 보상 호출. PENDING → FAILED 천이일 때만 stock 을 복구하고 hold 를 정리한다.
     * 멀티 인스턴스 sweeper 등이 동시 호출해도 비관적 락으로 직렬화 + status 검사로 멱등 보장.
     */
    @Transactional
    public Reservation fail(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        boolean wasPending = reservation.isPending();
        reservation.fail();
        if (wasPending) {
            productStockService.increase(reservation.getProductId());
            userProductHoldRepository.deleteByUserIdAndProductId(
                    reservation.getUserId(), reservation.getProductId());
        }
        return reservation;
    }

    @Transactional(readOnly = true)
    public Reservation findById(Long id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * reservation 생성 전 단계에서 실패한 경우(fallback 모드의 hold 만 영속화된 상태)의 cleanup.
     * 정상 모드에서는 hold 가 없어 no-op.
     */
    @Transactional
    public void releaseHold(Long userId, Long productId) {
        userProductHoldRepository.deleteByUserIdAndProductId(userId, productId);
    }
}
