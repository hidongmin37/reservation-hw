package com.hah.here.payment.gateway;

import com.hah.here.payment.method.MethodType;

import java.math.BigDecimal;

public interface PaymentGateway {

    /**
     * 외부 PG 결제 요청. 실패 시 예외 throw.
     * @return PG transaction ID
     */
    String charge(MethodType method, Long userId, BigDecimal amount, String referenceKey);

    /**
     * 외부 PG 환불 요청.
     */
    void refund(MethodType method, Long userId, BigDecimal amount, String referenceKey);
}
