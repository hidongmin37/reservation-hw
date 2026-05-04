package com.hah.here.stock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 앱 부팅 시 DB의 product_stock을 Redis 카운터로 동기화한다.
 *
 * 동작:
 *   - ApplicationReadyEvent 시점(Spring 컨텍스트 완전 준비 후) 실행
 *   - SETNX(`setIfAbsent`)로 기존 Redis 값이 있으면 보존 (운영 중 재시작 시 진행 중인 잔여를 덮어쓰지 않음)
 *   - 다중 인스턴스가 동시 부팅해도 SETNX 의미라 race-safe
 *
 * 한계:
 *   - Redis 컨테이너 재시작으로 키 소실 후 *앱이 이미 떠 있는* 상태에선 자동 복구 안 됨.
 *     (해당 케이스는 운영 시 admin 트리거 또는 헬스체크 기반 재초기화로 별도 대응 필요.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockInitializer {

    private static final String STOCK_KEY_PREFIX = "stock:";

    private final ProductStockRepository productStockRepository;
    private final StringRedisTemplate redisTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void initRedisCounters() {
        productStockRepository.findAll().forEach(stock -> {
            String key = STOCK_KEY_PREFIX + stock.getProductId();
            Boolean set = redisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(stock.getRemaining()));
            if (Boolean.TRUE.equals(set)) {
                log.info("Redis 재고 초기화: {} = {}", key, stock.getRemaining());
            } else {
                log.info("Redis 재고 기존 값 유지: {} (이미 존재)", key);
            }
        });
    }
}
