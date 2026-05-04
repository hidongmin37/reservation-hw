package com.hah.here.payment.method;

import com.hah.here.payment.gateway.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class YPayPayment implements PaymentMethod {

    private final PaymentGateway paymentGateway;

    @Override
    public MethodType type() {
        return MethodType.YPAY;
    }

    @Override
    public String charge(Long userId, BigDecimal amount, String referenceKey) {
        return paymentGateway.charge(MethodType.YPAY, userId, amount, referenceKey);
    }

    @Override
    public void refund(Long userId, BigDecimal amount, String referenceKey) {
        paymentGateway.refund(MethodType.YPAY, userId, amount, referenceKey);
    }
}
