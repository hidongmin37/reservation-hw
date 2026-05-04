package com.hah.here.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    /**
     * Sweeper / 보상 흐름이 PENDING → FAILED 천이를 직렬화하기 위해 비관적 락으로 선점.
     * 멀티 인스턴스 sweeper 가 동일 reservation 을 동시에 처리해도 stock 오버 증가 없음.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    /**
     * Sweeper: 지정 시각 이전에 생성된 PENDING reservation.
     * idx_reservation_status_created 인덱스로 가벼운 스캔.
     */
    @Query("select r from Reservation r " +
            "where r.status = com.hah.here.reservation.Reservation.Status.PENDING " +
            "and r.createdAt < :threshold")
    List<Reservation> findPendingOlderThan(@Param("threshold") LocalDateTime threshold);

    /**
     * Redis 정합 복원 시 사용. 활성(점유 중) 예약의 userId 집합.
     * PENDING + CONFIRMED 가 자리를 점유 중인 상태.
     */
    @Query("select r.userId from Reservation r " +
            "where r.productId = :productId and r.status in (com.hah.here.reservation.Reservation.Status.PENDING, com.hah.here.reservation.Reservation.Status.CONFIRMED)")
    List<Long> findActiveUserIdsByProductId(@Param("productId") Long productId);

    /**
     * DB Fallback: 1유저-1상품 검사. Redis entered: Set 의 DB-side 등가 역할.
     */
    @Query("select count(r) > 0 from Reservation r " +
            "where r.userId = :userId and r.productId = :productId " +
            "and r.status in (com.hah.here.reservation.Reservation.Status.PENDING, com.hah.here.reservation.Reservation.Status.CONFIRMED)")
    boolean existsActiveByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);
}
