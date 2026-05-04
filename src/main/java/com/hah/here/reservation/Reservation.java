package com.hah.here.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservation", indexes = {
        @Index(name = "idx_reservation_status_created", columnList = "status, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    public enum Status {
        PENDING,     // Phase A 통과, Phase B 결제 진행 중
        CONFIRMED,   // 결제 완료, 예약 확정
        FAILED,      // 결제 실패 (보상 완료)
        CANCELLED    // 사용자 취소 또는 sweeper 회수
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Reservation(Long userId, Long productId, BigDecimal totalAmount, String idempotencyKey) {
        this.userId = userId;
        this.productId = productId;
        this.totalAmount = totalAmount;
        this.idempotencyKey = idempotencyKey;
        this.status = Status.PENDING;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void confirm() {
        if (this.status != Status.PENDING) {
            throw new IllegalStateException("Cannot confirm reservation in status: " + status);
        }
        this.status = Status.CONFIRMED;
    }

    public void fail() {
        if (this.status == Status.CONFIRMED) {
            throw new IllegalStateException("Cannot fail already confirmed reservation");
        }
        this.status = Status.FAILED;
    }

    public void cancel() {
        this.status = Status.CANCELLED;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }
}
