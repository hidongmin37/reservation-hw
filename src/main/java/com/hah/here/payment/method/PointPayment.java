package com.hah.here.payment.method;

import com.hah.here.point.UserPointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class PointPayment implements PaymentMethod {

    private final UserPointService userPointService;

    @Override
    public MethodType type() {
        return MethodType.POINT;
    }

    @Override
    public String charge(Long userId, BigDecimal amount, String referenceKey) {
        userPointService.deduct(userId, amount);
        return null;
    }

    @Override
    public void refund(Long userId, BigDecimal amount, String referenceKey) {
        userPointService.refund(userId, amount);
    }
}
