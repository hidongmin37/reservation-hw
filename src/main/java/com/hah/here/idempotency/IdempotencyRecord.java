package com.hah.here.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 멱등성 영속 안전망.
 *
 * Redis 멱등 캐시는 정상 흐름의 *빠른 hit* 용. 본 테이블은 *Redis 장애 중에도 응답 재현*을 보장한다.
 *
 * 호출 정책:
 *  - 정상 모드: claim 은 Redis only (1000TPS 부담 회피), complete 는 DB 우선 + Redis best-effort.
 *  - fallback 모드 (Redis 장애): claim/complete/release 모두 DB 사용.
 */
@Entity
@Table(name = "idempotency_record",
        indexes = {
                @Index(name = "idx_idempotency_record_status_created", columnList = "status, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    public enum Status { INFLIGHT, COMPLETED }

    @Id
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Builder
    private IdempotencyRecord(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.status = Status.INFLIGHT;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public void markCompleted(String responseJson) {
        this.responseJson = responseJson;
        this.status = Status.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }
}
