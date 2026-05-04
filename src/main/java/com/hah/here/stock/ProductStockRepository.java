package com.hah.here.stock;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    /**
     * 비관적 쓰기 락. Phase B에서 차감/복구 시 사용한다.
     * Phase A를 통과한 ~10건만 도달하므로 락 경합 부담은 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ProductStock s where s.productId = :productId")
    Optional<ProductStock> findByProductIdForUpdate(@Param("productId") Long productId);
}
