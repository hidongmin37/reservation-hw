package com.hah.here.payment.gateway;

import com.hah.here.payment.method.MethodType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String charge(MethodType method, Long userId, BigDecimal amount, String referenceKey) {
        log.info("[MockPG] 결제 요청. 수단={}, 사용자={}, 금액={}, 참조키={}",
                method, userId, amount, referenceKey);
        return "MOCK-" + method + "-" + UUID.randomUUID();
    }

    @Override
    public void refund(MethodType method, Long userId, BigDecimal amount, String referenceKey) {
        log.info("[MockPG] 환불 요청. 수단={}, 사용자={}, 금액={}, 참조키={}",
                method, userId, amount, referenceKey);
    }
}
