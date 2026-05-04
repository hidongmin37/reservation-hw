package com.hah.here.booking;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingResponse(
        Long reservationId,
        String status,
        Long productId,
        BigDecimal totalAmount,
        LocalDateTime createdAt
) {
}
