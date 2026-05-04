package com.hah.here.stock;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import com.hah.here.reservation.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DB product_stock 의 차감/복구 + Redis 정합 복원.
 *
 * 차감/복구 메서드는 PROPAGATION REQUIRED 기본값 — 부모 TX(ReservationService)가 있으면 합류,
 * 없으면 자체 짧은 TX로 commit. 정합성과 외부 호출자의 트랜잭션 경계 모두 자연스럽다.
 *
 * rebuildRedis 는 운영 신호(Redis 키 소실 후 정합 복원)에 호출되는 admin-level 메서드.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductStockService {

    private static final String STOCK_KEY_PREFIX = "stock:";
    private static final String ENTERED_KEY_PREFIX = "entered:";
    /** 고아 자리 grace time. Phase A→B 정상 in-flight 와 사고로 누수된 자리 구분 */
    private static final long ORPHAN_GRACE_MILLIS = 5L * 60L * 1000L;

    private final ProductStockRepository productStockRepository;
    private final ReservationRepository reservationRepository;
    private final RedisStockGate redisStockGate;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public void decrease(Long productId) {
        ProductStock stock = productStockRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        stock.decrease();
    }

    @Transactional
    public void increase(Long productId) {
        ProductStock stock = productStockRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        stock.increase();
    }

    /**
     * Redis 키 소실 후 정합 복원. DB의 remaining 을 진실로 사용.
     * remaining 자체가 PENDING/CONFIRMED 차감을 반영한 *현재 잔여* 이므로 그대로 set.
     * entered: ZSET 은 활성 예약의 userId(score=현재 시각) 로 재구성.
     *
     * **호출 주체**: 운영자 명시 트리거(admin endpoint / health-check) 만.
     * 요청 경로(Phase A의 KEY_MISSING)에서는 호출하지 않는다.
     * 이유: 동시 다발 KEY_MISSING 응답을 받은 여러 요청이 각자 rebuild 를 실행하면
     *      *먼저 rebuild 후 DECR 한 값을 늦은 rebuild 가 DB remaining 으로 덮어써* 초과 admission 발생.
     */
    @Transactional(readOnly = true)
    public void rebuildRedis(Long productId) {
        ProductStock stock = productStockRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        String stockKey = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().set(stockKey, String.valueOf(stock.getRemaining()));

        String enteredKey = ENTERED_KEY_PREFIX + productId;
        redisTemplate.delete(enteredKey);
        List<Long> activeUserIds = reservationRepository.findActiveUserIdsByProductId(productId);
        if (!activeUserIds.isEmpty()) {
            double now = (double) System.currentTimeMillis();
            for (Long userId : activeUserIds) {
                redisTemplate.opsForZSet().add(enteredKey, String.valueOf(userId), now);
            }
        }
        log.info("Redis 정합 복원 완료. productId={}, remaining={}, entered={}건",
                productId, stock.getRemaining(), activeUserIds.size());
    }

    /**
     * 고아 자리 회수.
     * Phase A(Redis reserve) 직후 reservation INSERT 전에 서버가 죽은 경우,
     * entered: ZSET 에는 userId 가 남지만 DB 에는 reservation 이 없다.
     *
     * **grace time**: 정상 흐름에서도 Phase A→reservation INSERT 사이 짧은 in-flight 윈도우가 있어
     * DB 에 active reservation 이 *아직* 없는 정상 자리가 존재한다.
     * 이를 false-orphan 으로 오판하지 않도록 ZSET score(reservedAt) 가 NOW - 5분 보다 오래된 것만 검사.
     */
    @Transactional(readOnly = true)
    public int cleanupOrphanedSeats(Long productId) {
        String enteredKey = ENTERED_KEY_PREFIX + productId;
        long graceThreshold = System.currentTimeMillis() - ORPHAN_GRACE_MILLIS;
        Set<ZSetOperations.TypedTuple<String>> oldEntries = redisTemplate.opsForZSet()
                .rangeByScoreWithScores(enteredKey, Double.NEGATIVE_INFINITY, graceThreshold);
        if (oldEntries == null || oldEntries.isEmpty()) {
            return 0;
        }
        Set<String> activeUserIds = reservationRepository.findActiveUserIdsByProductId(productId)
                .stream().map(String::valueOf).collect(Collectors.toCollection(HashSet::new));

        int released = 0;
        for (ZSetOperations.TypedTuple<String> entry : oldEntries) {
            String userIdStr = entry.getValue();
            if (userIdStr == null) {
                continue;
            }
            if (!activeUserIds.contains(userIdStr)) {
                try {
                    redisStockGate.release(Long.parseLong(userIdStr), productId);
                    released++;
                } catch (NumberFormatException ignored) {
                    log.warn("entered: ZSET 에 비정상 멤버 발견. productId={}, member={}", productId, userIdStr);
                }
            }
        }
        if (released > 0) {
            log.info("고아 자리 회수(grace>{}ms). productId={}, 회수 건수={}",
                    ORPHAN_GRACE_MILLIS, productId, released);
        }
        return released;
    }
}
