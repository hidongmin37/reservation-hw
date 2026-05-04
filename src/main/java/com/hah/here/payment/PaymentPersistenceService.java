package com.hah.here.payment;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentPersistenceService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public Payment createPending(Long reservationId, PaymentRequest req) {
        return paymentRepository.save(
                Payment.builder()
                        .reservationId(reservationId)
                        .method(req.method())
                        .amount(req.amount())
                        .build()
        );
    }

    @Transactional
    public Payment markSuccess(Long paymentId, String pgTxId) {
        Payment payment = findById(paymentId);
        payment.markSuccess(pgTxId);
        return payment;
    }

    @Transactional
    public void markRefunded(Long paymentId) {
        Payment payment = findById(paymentId);
        payment.markRefunded();
    }

    /**
     * 환불 권한 획득 시도 (SUCCESS → REFUNDING 조건부 transition).
     * 1 row 영향이면 권한 획득, 0 row 면 다른 인스턴스가 이미 처리 중/완료.
     */
    @Transactional
    public boolean tryAcquireRefund(Long paymentId) {
        return paymentRepository.tryAcquireRefund(paymentId) == 1;
    }

    private Payment findById(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
    }
}
