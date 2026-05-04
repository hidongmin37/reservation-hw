package com.hah.here.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByReservationId(Long reservationId);

    /**
     * 환불 권한 분배.
     * (PENDING 또는 SUCCESS) → REFUNDING 조건부 update. 영향 row 수 = 1 인 인스턴스만 PG refund 호출.
     * 멀티 인스턴스 sweeper 동시 진입 시 외부 PG 이중 호출 방지.
     *
     * PENDING 도 환불 대상에 포함하는 이유: PaymentOrchestrator 가 PG charge() 성공 직후
     * processed 에 등록한 후 markSuccess 가 실패하면 DB status 는 PENDING 으로 잔존하지만
     * *PG 측에서는 이미 결제가 발생*한 상태. 환불을 시도해야 한다.
     * (PG idempotency-key 보장 가정 — 본 mock 은 paymentId 로 환불 호출, 운영 PG 도 동일 패턴)
     */
    @Modifying
    @Query("update Payment p set p.status = com.hah.here.payment.Payment.Status.REFUNDING " +
            "where p.id = :id and p.status in (" +
            "com.hah.here.payment.Payment.Status.PENDING, " +
            "com.hah.here.payment.Payment.Status.SUCCESS)")
    int tryAcquireRefund(@Param("id") Long id);
}
