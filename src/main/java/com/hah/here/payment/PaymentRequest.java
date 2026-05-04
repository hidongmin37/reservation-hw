package com.hah.here.payment;

import com.hah.here.payment.method.MethodType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull MethodType method,
        @NotNull @Positive BigDecimal amount
) {
}
