package com.hah.here.payment.method;

import com.hah.here.payment.gateway.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class CardPayment implements PaymentMethod {

    private final PaymentGateway paymentGateway;

    @Override
    public MethodType type() {
        return MethodType.CARD;
    }

    @Override
    public String charge(Long userId, BigDecimal amount, String referenceKey) {
        return paymentGateway.charge(MethodType.CARD, userId, amount, referenceKey);
    }

    @Override
    public void refund(Long userId, BigDecimal amount, String referenceKey) {
        paymentGateway.refund(MethodType.CARD, userId, amount, referenceKey);
    }
}
