package com.hah.here.product;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 상품 정보 read-through 캐시.
 *
 * 거절 경로(Phase A에서 SOLD_OUT)도 product 검증을 거치므로 1000TPS 가 그대로 DB 에 도달.
 * HikariCP(30) + 인덱스 read 라도 1000+ 동시 호출은 큐잉 발생 → 거절 latency 폭증.
 *
 * product 는 거의 변경되지 않는 데이터이므로 단순 in-memory 캐시.
 * 운영 시 상품 정보 갱신은 admin 흐름에서 evict 호출하면 됨 (현 범위 외).
 *
 * Caffeine/spring-cache 미사용 이유: 의존성 추가 대비 효과 동일. 본 규모(상품 수 < 100)에선
 * ConcurrentHashMap 으로 충분. TTL/size 제한 불필요.
 *
 * @Transactional(readOnly=true) 부착 이유: SimpleJpaRepository 가 자체 TX 를 부여하긴 하지만
 * 메서드 레벨 명시가 *의도 표현 + 미래 변경 안전망* 측면에서 정석. cache hit 경로의 TX 비용은
 * connection 미점유 (실제 query 시에만 connection acquire) 로 사실상 0.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final Map<Long, Product> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Product getById(Long id) {
        Product cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        Product loaded = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        cache.putIfAbsent(id, loaded);
        return loaded;
    }

    /** 상품 정보 변경 시 호출. 현재는 사용처 없음. */
    public void evict(Long id) {
        cache.remove(id);
    }
}
