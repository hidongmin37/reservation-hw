package com.hah.here.payment;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import com.hah.here.payment.method.MethodType;
import com.hah.here.payment.method.PaymentMethod;
import com.hah.here.reservation.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentOrchestrator 의 Saga 흐름 + 보상 로직 검증.
 * 외부 의존 (PaymentMethod, PaymentPersistenceService) 모두 mock.
 */
class PaymentOrchestratorTest {

    private PaymentMethod cardMethod;
    private PaymentMethod ypayMethod;
    private PaymentMethod pointMethod;
    private PaymentPersistenceService persistenceService;

    private PaymentOrchestrator orchestrator;

    private Reservation reservation;

    @BeforeEach
    void setUp() {
        cardMethod = mockMethod(MethodType.CARD);
        ypayMethod = mockMethod(MethodType.YPAY);
        pointMethod = mockMethod(MethodType.POINT);
        persistenceService = mock(PaymentPersistenceService.class);

        orchestrator = new PaymentOrchestrator(
                List.of(cardMethod, ypayMethod, pointMethod),
                persistenceService
        );

        reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(1L);
        when(reservation.getUserId()).thenReturn(100L);
    }

    private PaymentMethod mockMethod(MethodType type) {
        PaymentMethod m = mock(PaymentMethod.class);
        when(m.type()).thenReturn(type);
        return m;
    }

    private Payment mockPayment(Long id, MethodType method, BigDecimal amount) {
        Payment p = mock(Payment.class);
        when(p.getId()).thenReturn(id);
        when(p.getMethod()).thenReturn(method);
        when(p.getAmount()).thenReturn(amount);
        return p;
    }

    @Nested
    @DisplayName("정상 흐름")
    class Success {

        @Test
        @DisplayName("단일 POINT 결제 — createPending → charge → markSuccess 순서로 1회 호출")
        void singlePoint() {
            Payment payment = mockPayment(10L, MethodType.POINT, new BigDecimal("50000"));
            when(persistenceService.createPending(eq(1L), any())).thenReturn(payment);
            when(pointMethod.charge(eq(100L), any(), eq("10"))).thenReturn(null);

            List<Payment> processed = orchestrator.process(
                    reservation,
                    List.of(new PaymentRequest(MethodType.POINT, new BigDecimal("50000")))
            );

            assertThat(processed).hasSize(1);
            InOrder inOrder = inOrder(persistenceService, pointMethod);
            inOrder.verify(persistenceService).createPending(eq(1L), any());
            inOrder.verify(pointMethod).charge(eq(100L), any(), eq("10"));
            inOrder.verify(persistenceService).markSuccess(eq(10L), any());
        }

        @Test
        @DisplayName("복합 CARD+POINT — POINT 먼저 처리 (Saga 순서: 내부 자원 → 외부 자원)")
        void compositeCardPlusPoint_pointFirst() {
            Payment cardPayment = mockPayment(20L, MethodType.CARD, new BigDecimal("20000"));
            Payment pointPayment = mockPayment(21L, MethodType.POINT, new BigDecimal("30000"));

            when(persistenceService.createPending(eq(1L), any()))
                    .thenReturn(pointPayment, cardPayment);
            when(pointMethod.charge(anyLong(), any(), anyString())).thenReturn(null);
            when(cardMethod.charge(anyLong(), any(), anyString())).thenReturn("PG_TX_123");

            // 요청은 CARD 먼저 들어오지만 orchestrator 가 POINT 먼저 정렬
            orchestrator.process(reservation, List.of(
                    new PaymentRequest(MethodType.CARD, new BigDecimal("20000")),
                    new PaymentRequest(MethodType.POINT, new BigDecimal("30000"))
            ));

            InOrder inOrder = inOrder(pointMethod, cardMethod);
            inOrder.verify(pointMethod).charge(anyLong(), any(), anyString());
            inOrder.verify(cardMethod).charge(anyLong(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("조합 검증")
    class Composition {

        @Test
        @DisplayName("CARD+YPAY 금지 — INVALID_PAYMENT_COMBINATION 발생, 어떤 결제도 진행 안 함")
        void cardPlusYpay_rejected() {
            assertThatThrownBy(() -> orchestrator.process(reservation, List.of(
                    new PaymentRequest(MethodType.CARD, new BigDecimal("25000")),
                    new PaymentRequest(MethodType.YPAY, new BigDecimal("25000"))
            )))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PAYMENT_COMBINATION);

            verify(persistenceService, never()).createPending(anyLong(), any());
            verify(cardMethod, never()).charge(anyLong(), any(), anyString());
            verify(ypayMethod, never()).charge(anyLong(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Saga 보상")
    class SagaCompensation {

        @Test
        @DisplayName("CARD charge 실패 — 이전 POINT 환불 (역순) 후 PAYMENT_FAILED throw")
        void cardChargeFails_pointRefunded() {
            Payment pointPayment = mockPayment(30L, MethodType.POINT, new BigDecimal("30000"));
            Payment cardPayment = mockPayment(31L, MethodType.CARD, new BigDecimal("20000"));

            when(persistenceService.createPending(eq(1L), any()))
                    .thenReturn(pointPayment, cardPayment);
            when(pointMethod.charge(anyLong(), any(), anyString())).thenReturn(null);
            doThrow(new RuntimeException("PG 한도 초과"))
                    .when(cardMethod).charge(anyLong(), any(), anyString());

            // refundAll 이 tryAcquireRefund 통과시킴
            when(persistenceService.tryAcquireRefund(anyLong())).thenReturn(true);

            assertThatThrownBy(() -> orchestrator.process(reservation, List.of(
                    new PaymentRequest(MethodType.POINT, new BigDecimal("30000")),
                    new PaymentRequest(MethodType.CARD, new BigDecimal("20000"))
            )))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_FAILED);

            // CARD 환불 먼저 시도 (역순) — 단 charge 가 throw 했으니 CARD 자체는 환불 시도 *안 함*?
            // 정확히 — processed 에 들어간 payment 만 환불. CARD 는 charge throw 라 processed.add 안 됨.
            // POINT 만 환불됨.
            verify(pointMethod).refund(eq(100L), any(), eq("30"));
            verify(cardMethod, never()).refund(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("markSuccess 실패해도 환불 누락 차단 — processed.add 가 charge 직후라 환불 대상에 포함")
        void markSuccessFails_stillRefunded() {
            Payment payment = mockPayment(40L, MethodType.YPAY, new BigDecimal("50000"));
            when(persistenceService.createPending(eq(1L), any())).thenReturn(payment);
            when(ypayMethod.charge(anyLong(), any(), anyString())).thenReturn("PG_TX_456");
            // markSuccess 가 throw — DB 갱신 실패 시뮬
            doThrow(new RuntimeException("DB connection lost"))
                    .when(persistenceService).markSuccess(eq(40L), anyString());

            when(persistenceService.tryAcquireRefund(eq(40L))).thenReturn(true);

            assertThatThrownBy(() -> orchestrator.process(reservation,
                    List.of(new PaymentRequest(MethodType.YPAY, new BigDecimal("50000")))))
                    .isInstanceOf(BusinessException.class);

            // PG 결제 성공했으니 환불 시도 — 우리 fix 핵심
            verify(ypayMethod).refund(eq(100L), any(), eq("40"));
        }
    }

    @Nested
    @DisplayName("refundAll 멱등성")
    class RefundIdempotent {

        @Test
        @DisplayName("tryAcquireRefund 가 false 인 결제는 PG refund 호출 생략")
        void alreadyRefunded_skipped() {
            Payment p1 = mockPayment(50L, MethodType.CARD, new BigDecimal("20000"));
            Payment p2 = mockPayment(51L, MethodType.POINT, new BigDecimal("30000"));

            // p1 은 다른 인스턴스가 이미 환불 진행 중 → 권한 미획득
            when(persistenceService.tryAcquireRefund(eq(50L))).thenReturn(false);
            when(persistenceService.tryAcquireRefund(eq(51L))).thenReturn(true);

            orchestrator.refundAll(100L, List.of(p1, p2));

            verify(cardMethod, never()).refund(anyLong(), any(), anyString());
            verify(pointMethod).refund(eq(100L), any(), eq("51"));
        }

        @Test
        @DisplayName("역순 처리 — 마지막 결제부터 환불 (외부 자원 먼저)")
        void reverseOrder() {
            Payment pointFirst = mockPayment(60L, MethodType.POINT, new BigDecimal("30000"));
            Payment cardLast = mockPayment(61L, MethodType.CARD, new BigDecimal("20000"));

            when(persistenceService.tryAcquireRefund(anyLong())).thenReturn(true);

            // 입력 순서: [POINT, CARD] → 환불 순서: [CARD, POINT]
            orchestrator.refundAll(100L, List.of(pointFirst, cardLast));

            InOrder inOrder = inOrder(cardMethod, pointMethod);
            inOrder.verify(cardMethod).refund(eq(100L), any(), eq("61"));
            inOrder.verify(pointMethod).refund(eq(100L), any(), eq("60"));
        }

        @Test
        @DisplayName("PG refund 자체가 실패해도 다른 결제 환불은 계속 시도")
        void refundFailureDoesNotStopOthers() {
            Payment p1 = mockPayment(70L, MethodType.CARD, new BigDecimal("20000"));
            Payment p2 = mockPayment(71L, MethodType.POINT, new BigDecimal("30000"));

            when(persistenceService.tryAcquireRefund(anyLong())).thenReturn(true);
            doThrow(new RuntimeException("PG 환불 실패"))
                    .when(pointMethod).refund(anyLong(), any(), eq("71"));

            // 예외 propagate 하지 않음
            orchestrator.refundAll(100L, List.of(p1, p2));

            verify(cardMethod).refund(anyLong(), any(), eq("70"));
            verify(pointMethod).refund(anyLong(), any(), eq("71")); // 실패해도 시도
        }

        @Test
        @DisplayName("빈 list / null — no-op")
        void emptyOrNull() {
            orchestrator.refundAll(100L, null);
            orchestrator.refundAll(100L, List.of());

            verify(cardMethod, never()).refund(anyLong(), any(), anyString());
            verify(persistenceService, never()).tryAcquireRefund(anyLong());
        }
    }
}
