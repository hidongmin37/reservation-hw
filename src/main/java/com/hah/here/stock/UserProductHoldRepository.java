package com.hah.here.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface UserProductHoldRepository extends JpaRepository<UserProductHold, Long> {

    @Modifying
    @Query("delete from UserProductHold h where h.userId = :userId and h.productId = :productId")
    int deleteByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * Sweeper 응급 정리. 오래된 hold 중 active reservation 이 *없는* 것만 삭제.
     * (active reservation 이 있는 hold 는 정상 라이프사이클 진행 중 → 보존)
     */
    @Modifying
    @Query("delete from UserProductHold h " +
            "where h.heldAt < :threshold and not exists (" +
            "  select 1 from Reservation r " +
            "  where r.userId = h.userId and r.productId = h.productId " +
            "  and r.status in (com.hah.here.reservation.Reservation.Status.PENDING, " +
            "                   com.hah.here.reservation.Reservation.Status.CONFIRMED))")
    int deleteStaleOrphans(@Param("threshold") LocalDateTime threshold);
}
