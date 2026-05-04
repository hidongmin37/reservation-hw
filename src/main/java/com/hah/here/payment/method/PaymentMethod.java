package com.hah.here.payment.method;

import java.math.BigDecimal;

public interface PaymentMethod {

    MethodType type();

    /**
     * 결제 처리. 실패 시 예외 throw.
     * @return 외부 PG transaction ID. 내부 결제(POINT)는 null 반환.
     */
    String charge(Long userId, BigDecimal amount, String referenceKey);

    /**
     * 환불 처리. 실패 시 예외 throw (best effort).
     */
    void refund(Long userId, BigDecimal amount, String referenceKey);
}
