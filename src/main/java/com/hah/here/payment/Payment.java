package com.hah.here.payment;

import com.hah.here.payment.method.MethodType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    public enum Status { PENDING, SUCCESS, FAILED, REFUNDING, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MethodType method;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "pg_transaction_id", length = 64)
    private String pgTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Payment(Long reservationId, MethodType method, BigDecimal amount) {
        this.reservationId = reservationId;
        this.method = method;
        this.amount = amount;
        this.status = Status.PENDING;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public void markSuccess(String pgTxId) {
        this.pgTransactionId = pgTxId;
        this.status = Status.SUCCESS;
    }

    public void markFailed() {
        this.status = Status.FAILED;
    }

    /**
     * 환불 진행 중 마킹. 보통은 PaymentRepository.tryAcquireRefund 의 조건부 UPDATE 로 처리하고,
     * 이 메서드는 이미 영속화된 객체를 메모리에서 동기화할 때 사용.
     */
    public void markRefunding() {
        this.status = Status.REFUNDING;
    }

    public void markRefunded() {
        this.status = Status.REFUNDED;
    }
}
