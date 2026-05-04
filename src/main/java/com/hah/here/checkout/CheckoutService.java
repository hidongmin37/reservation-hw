package com.hah.here.checkout;

import com.hah.here.product.Product;
import com.hah.here.product.ProductService;
import com.hah.here.point.UserPointService;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final String STOCK_KEY_PREFIX = "stock:";

    private final ProductService productService;
    private final UserPointService userPointService;
    private final StringRedisTemplate redisTemplate;

    /**
     * @Bulkhead("checkout"): booking burst 시에도 checkout 응답 안정성 확보.
     * checkout 이 booking 과 같은 자원 풀을 *무한 점유* 하지 않게 동시 진입 한계.
     * 한계 초과 시 BulkheadFullException → 503.
     */
    @Bulkhead(name = "checkout")
    public CheckoutResponse getCheckout(Long productId, Long userId) {
        Product product = productService.getById(productId);
        BigDecimal availablePoint = userPointService.getBalance(userId);
        Integer remainingStock = readRemainingStock(productId);

        return new CheckoutResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getCheckInAt(),
                product.getCheckOutAt(),
                product.getOpenAt(),
                remainingStock,
                availablePoint
        );
    }

    private Integer readRemainingStock(Long productId) {
        String value = redisTemplate.opsForValue().get(STOCK_KEY_PREFIX + productId);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
