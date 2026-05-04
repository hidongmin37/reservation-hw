package com.hah.here.checkout;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CheckoutResponse(
        Long productId,
        String name,
        String description,
        BigDecimal price,
        LocalDateTime checkInAt,
        LocalDateTime checkOutAt,
        LocalDateTime openAt,
        Integer remainingStock,
        BigDecimal availablePoint
) {
}
