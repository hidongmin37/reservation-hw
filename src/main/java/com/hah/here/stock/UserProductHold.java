package com.hah.here.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DB Fallback 모드의 1유저-1상품 hold.
 *
 * 정상 흐름은 Redis entered: ZSET 이 1u1p 안전망. Fallback 모드(Redis 장애)에서는
 * 같은 user_id+product_id 동시 두 INSERT 중 하나만 통과시키기 위해 UNIQUE 제약 사용.
 *
 * Hold 의 라이프사이클: DbStockFallback.reserve 에서 INSERT,
 * ReservationService.confirm/fail 에서 cleanup. 누수된 hold 는 sweeper 가 stale 로 회수.
 */
@Entity
@Table(name = "user_product_hold",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_product", columnNames = {"user_id", "product_id"})
        },
        indexes = {
                @Index(name = "idx_user_product_hold_held_at", columnList = "held_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProductHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "held_at", nullable = false)
    private LocalDateTime heldAt;

    @Builder
    private UserProductHold(Long userId, Long productId) {
        this.userId = userId;
        this.productId = productId;
    }

    @PrePersist
    void prePersist() {
        this.heldAt = LocalDateTime.now();
    }
}
