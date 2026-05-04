package com.hah.here.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import com.hah.here.product.Product;
import com.hah.here.product.ProductService;
import com.hah.here.idempotency.IdempotencyOutcome;
import com.hah.here.idempotency.IdempotencyService;
import com.hah.here.payment.Payment;
import com.hah.here.payment.PaymentOrchestrator;
import com.hah.here.payment.PaymentRequest;
import com.hah.here.reservation.Reservation;
import com.hah.here.reservation.ReservationService;
import com.hah.here.stock.RedisStockGate;
import com.hah.here.stock.ReservationResult;
import com.hah.here.stock.ReservationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 오케스트레이션. Phase A(Redis) → Phase B(DB+Payment)를 묶어 처리.
 *
 * 흐름:
 *   1. 멱등 claim (FIRST/IN_FLIGHT/CACHED 분기)
 *   2. Product 조회 + 오픈 시간 검증
 *   3. RedisStockGate.reserve() — 재고 확보
 *   4. ReservationService.create() — PENDING 영속화
 *   5. PaymentOrchestrator.process() — Saga 결제 (성공한 결제 목록 반환)
 *   6. ReservationService.confirm() — CONFIRMED 마킹
 *   7. IdempotencyService.complete() — 응답 캐시
 *
 * 실패 시 보상 (compensate):
 *   - 결제 환불 (process() 통과 후 후속 단계 실패 시)
 *   - reservation.fail()
 *   - redisStockGate.release()
 *   - idempotency.release()
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final IdempotencyService idempotencyService;
    private final RedisStockGate redisStockGate;
    private final ProductService productService;
    private final ReservationService reservationService;
    private final PaymentOrchestrator paymentOrchestrator;
    private final ObjectMapper objectMapper;

    /**
     * Bulkhead 미적용: booking 전체에 bulkhead 를 걸면 *매진 요청까지* 503 으로 잘려
     * 선착순 의도와 충돌. Phase A(Redis admission gate) 가 정상적으로 SOLD_OUT (409) 를
     * 만들도록 두고, 시스템 보호는 RateLimiter(DbStockFallback) + Tomcat thread/accept-count 가 담당.
     */
    public BookingResponse book(BookingRequest request, String headerIdempotencyKey) {
        Long userId = request.userId();
        Long productId = request.productId();

        String idempKey = idempotencyService.resolveKey(headerIdempotencyKey, userId, productId);

        IdempotencyOutcome outcome = idempotencyService.claim(idempKey);
        if (outcome.isCached()) {
            return deserializeCached(outcome.cachedJson());
        }
        if (outcome.isInFlight()) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        }

        Product product = productService.getById(productId);
        if (!product.isOpenForBooking(LocalDateTime.now())) {
            idempotencyService.release(idempKey);
            throw new BusinessException(ErrorCode.PRODUCT_NOT_OPEN);
        }

        BigDecimal totalAmount = sumAmounts(request.paymentMethods());
        if (totalAmount.compareTo(product.getPrice()) != 0) {
            log.warn("결제 금액 불일치. userId={}, productId={}, expected={}, actual={}",
                    userId, productId, product.getPrice(), totalAmount);
            idempotencyService.release(idempKey);
            throw new BusinessException(ErrorCode.AMOUNT_MISMATCH);
        }

        ReservationResult gateResult = redisStockGate.reserve(userId, productId);
        if (!gateResult.isSuccess()) {
            idempotencyService.release(idempKey);
            throw mapGateFailure(gateResult.status());
        }

        Reservation reservation = null;
        List<Payment> processedPayments = null;
        BookingResponse response;
        try {
            reservation = reservationService.create(userId, productId, totalAmount, idempKey);

            processedPayments = paymentOrchestrator.process(reservation, request.paymentMethods());
            reservationService.confirm(reservation.getId());

            response = new BookingResponse(
                    reservation.getId(),
                    Reservation.Status.CONFIRMED.name(),
                    productId,
                    totalAmount,
                    reservation.getCreatedAt()
            );
        } catch (Exception e) {
            log.error("예약 처리 실패. userId={}, productId={}, 보상 시작", userId, productId, e);
            compensate(userId, productId, idempKey, reservation, processedPayments);
            throw e;
        }

        // confirm 이후의 멱등 응답 저장 실패는 *예약 성공을 되돌리지 않는다*.
        // 결제/예약은 이미 완료된 사용자에게 cached response 못 주는 UX 손실만 있을 뿐,
        // 보상 호출(환불) 시 *완료된 예약을 잘못 되돌리는* 것이 더 큰 모순.
        // 재시도는 reservation.idempotency_key UNIQUE 가 막아 중복 booking 차단.
        try {
            idempotencyService.complete(idempKey, response);
        } catch (Exception e) {
            log.error("예약 확정 후 멱등 응답 저장 실패. reservationId={} (예약은 보존)",
                    reservation.getId(), e);
        }
        return response;
    }

    private void compensate(Long userId, Long productId, String idempKey,
                            Reservation reservation, List<Payment> processedPayments) {
        if (processedPayments != null && !processedPayments.isEmpty()) {
            try {
                paymentOrchestrator.refundAll(userId, processedPayments);
            } catch (Exception e) {
                log.error("결제 환불 실패. userId={}, 수동 정산 필요", userId, e);
            }
        }
        if (reservation != null) {
            try {
                reservationService.fail(reservation.getId());
            } catch (Exception e) {
                log.error("예약 FAILED 마킹 실패. id={}", reservation.getId(), e);
            }
        } else {
            // reservation 생성 전 단계 실패 — fallback 모드의 hold 가 잔존할 수 있어 정리.
            // (정상 모드에서는 hold 가 없어 no-op)
            try {
                reservationService.releaseHold(userId, productId);
            } catch (Exception e) {
                log.error("hold cleanup 실패. sweeper 가 회수 예정. userId={}, productId={}", userId, productId, e);
            }
        }
        try {
            redisStockGate.release(userId, productId);
        } catch (Exception e) {
            log.error("Redis 자리 해제 실패. userId={}, productId={}", userId, productId, e);
        }
        idempotencyService.release(idempKey);
    }

    private BigDecimal sumAmounts(List<PaymentRequest> requests) {
        return requests.stream()
                .map(PaymentRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BusinessException mapGateFailure(ReservationStatus status) {
        return switch (status) {
            case ALREADY_RESERVED -> new BusinessException(ErrorCode.ALREADY_RESERVED);
            case SOLD_OUT -> new BusinessException(ErrorCode.SOLD_OUT);
            default -> new BusinessException(ErrorCode.INTERNAL_ERROR);
        };
    }

    private BookingResponse deserializeCached(String json) {
        try {
            return objectMapper.readValue(json, BookingResponse.class);
        } catch (JsonProcessingException e) {
            log.error("멱등 캐시 응답 역직렬화 실패", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
