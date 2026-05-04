package com.hah.here.payment;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import com.hah.here.payment.method.MethodType;
import com.hah.here.payment.method.PaymentMethod;
import com.hah.here.reservation.Reservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 결제 Saga 오케스트레이터.
 *
 * 트랜잭션 경계: 클래스/메서드 레벨 @Transactional 미사용.
 * 각 단계가 독립 트랜잭션으로 짧게 commit되어 외부 PG 호출이 DB 커넥션을 점유하지 않는다.
 *
 * 흐름:
 *   1. PaymentComposition.validate() — 조합 정책 검증
 *   2. POINT 우선 정렬 (Saga 순서)
 *   3. 각 결제별 3단계:
 *      TX1: PaymentPersistenceService.createPending()
 *      (no TX): PaymentMethod.charge() — POINT는 자체 짧은 TX, CARD/YPAY는 외부 HTTP
 *      TX2: PaymentPersistenceService.markSuccess()
 *   4. 실패 시 역순 보상 (refundAll)
 *
 * 외부 호출자(BookingService 등)도 process() 통과 후 후속 단계 실패 시
 * refundAll()을 호출하여 명시적 환불 가능.
 */
@Service
@Slf4j
public class PaymentOrchestrator {

    private final Map<MethodType, PaymentMethod> methods;
    private final PaymentPersistenceService persistenceService;

    public PaymentOrchestrator(List<PaymentMethod> methodList,
                               PaymentPersistenceService persistenceService) {
        this.methods = methodList.stream()
                .collect(Collectors.toMap(PaymentMethod::type, Function.identity()));
        this.persistenceService = persistenceService;
    }

    public List<Payment> process(Reservation reservation, List<PaymentRequest> requests) {
        Set<MethodType> requestedTypes = requests.stream()
                .map(PaymentRequest::method)
                .collect(Collectors.toSet());
        PaymentComposition.validate(requestedTypes);

        List<PaymentRequest> ordered = sortByPointFirst(requests);

        List<Payment> processed = new ArrayList<>();
        try {
            for (PaymentRequest req : ordered) {
                PaymentMethod method = findMethod(req.method());

                Payment payment = persistenceService.createPending(reservation.getId(), req);

                String pgTxId = method.charge(
                        reservation.getUserId(),
                        req.amount(),
                        payment.getId().toString()
                );

                // PG 호출 성공 직후 processed 에 등록 — *환불 대상 확정*.
                // 이후 markSuccess 가 실패하더라도(예: DB 끊김) catch 의 refundAll 이 환불 시도.
                // tryAcquireRefund 는 PENDING/SUCCESS 모두 처리하도록 확장됨 (PaymentRepository).
                processed.add(payment);

                payment = persistenceService.markSuccess(payment.getId(), pgTxId);
            }
            return processed;
        } catch (Exception e) {
            log.error("결제 실패. 예약={}, 이전 성공 결제 {}건 보상 시작",
                    reservation.getId(), processed.size(), e);
            refundAll(reservation.getUserId(), processed);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }
    }

    /**
     * 외부 호출용 환불 메서드. process() 통과 후 후속 단계 실패 시 / Sweeper 회수 시 사용.
     * 역순 처리(Saga 보상 순서) — 외부 자원(CARD/YPAY) 먼저, 내부 자원(POINT) 마지막.
     *
     * 멱등성: payment row 의 SUCCESS → REFUNDING 조건부 transition 으로 권한을 분배한다.
     * 멀티 인스턴스 sweeper 가 동시에 같은 reservation 을 잡아도 외부 PG 환불은 1회만 발생.
     */
    public void refundAll(Long userId, List<Payment> payments) {
        if (payments == null || payments.isEmpty()) {
            return;
        }
        for (int i = payments.size() - 1; i >= 0; i--) {
            Payment p = payments.get(i);
            if (!persistenceService.tryAcquireRefund(p.getId())) {
                log.info("환불 권한 미획득(이미 처리 중/완료). paymentId={}", p.getId());
                continue;
            }
            try {
                findMethod(p.getMethod()).refund(userId, p.getAmount(), p.getId().toString());
                persistenceService.markRefunded(p.getId());
            } catch (Exception refundError) {
                log.error("환불 실패. paymentId={} REFUNDING 상태로 잔존 — 수동 정산 필요",
                        p.getId(), refundError);
            }
        }
    }

    private List<PaymentRequest> sortByPointFirst(List<PaymentRequest> requests) {
        return requests.stream()
                .sorted(Comparator.comparingInt(r -> r.method() == MethodType.POINT ? 0 : 1))
                .toList();
    }

    private PaymentMethod findMethod(MethodType type) {
        PaymentMethod m = methods.get(type);
        if (m == null) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
        return m;
    }
}
