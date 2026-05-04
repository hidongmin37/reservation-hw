package com.hah.here.booking;

import com.hah.here.payment.PaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BookingRequest(
        @NotNull Long userId,
        @NotNull Long productId,
        @NotEmpty @Valid List<PaymentRequest> paymentMethods
) {
}
