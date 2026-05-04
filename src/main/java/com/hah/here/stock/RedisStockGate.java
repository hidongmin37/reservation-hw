package com.hah.here.stock;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStockGate {

    private static final String STOCK_KEY_PREFIX = "stock:";
    private static final String ENTERED_KEY_PREFIX = "entered:";
    /** 매진 negative cache TTL — 짧게 (재오픈/보상 영향 최소화) */
    private static final long SOLDOUT_CACHE_MS = 1_000L;

    private final RedisLuaCaller redisLuaCaller;
    private final DbStockFallback dbStockFallback;

    /** productId → 매진 시각(epoch ms). 짧은 TTL 동안 즉시 거절 */
    private final Map<Long, Long> soldOutAt = new ConcurrentHashMap<>();

    public ReservationResult reserve(Long userId, Long productId) {
        // Local negative cache hit → Lua 호출 생략
        Long cachedAt = soldOutAt.get(productId);
        long now = System.currentTimeMillis();
        if (cachedAt != null && now - cachedAt < SOLDOUT_CACHE_MS) {
            return ReservationResult.soldOut();
        }

        ReservationResult result = redisLuaCaller.reserve(stockKey(productId), enteredKey(productId), userId);

        // 결과별 cache 갱신
        switch (result.status()) {
            case SOLD_OUT -> soldOutAt.put(productId, now);
            case SUCCESS -> soldOutAt.remove(productId);
            // ALREADY_RESERVED 는 cache 무관 — 다른 사용자에겐 영향 X
            // KEY_MISSING / REDIS_DOWN 은 cache 안 함 (매진 정보 신뢰 불가)
            default -> { }
        }

        if (result.status() == ReservationStatus.KEY_MISSING
                || result.status() == ReservationStatus.REDIS_DOWN) {
            log.warn("Redis admission 비정상. DB Fallback 우회. productId={}, status={}",
                    productId, result.status());
            return safeFallback(userId, productId);
        }
        return result;
    }

    public void release(Long userId, Long productId) {
        // 재고 복구되니 매진 cache 무효화 (false-negative 빠르게 해소)
        soldOutAt.remove(productId);
        redisLuaCaller.release(stockKey(productId), enteredKey(productId), userId);
        // CB OPEN 시 fallback method 가 swallow.
        // DB 측 stock 복구는 reservationService.fail() 이 자체적으로 수행.
    }

    private ReservationResult safeFallback(Long userId, Long productId) {
        try {
            return dbStockFallback.reserve(userId, productId);
        } catch (RequestNotPermitted e) {
            log.warn("DB Fallback rate limit 초과 — 피크 트래픽 보호. userId={}, productId={}", userId, productId);
            throw new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
        }
    }

    private String stockKey(Long productId) {
        return STOCK_KEY_PREFIX + productId;
    }

    private String enteredKey(Long productId) {
        return ENTERED_KEY_PREFIX + productId;
    }
}
