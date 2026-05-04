package com.hah.here.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Product (정상 거절)
    PRODUCT_NOT_FOUND("PRODUCT_001", "상품을 찾을 수 없습니다.", 404, false),
    PRODUCT_NOT_OPEN("PRODUCT_002", "판매 오픈 시간 전입니다.", 400, false),

    // Stock (정상 거절 — 알람 가치 없음)
    SOLD_OUT("STOCK_001", "재고가 모두 소진되었습니다.", 409, false),
    ALREADY_RESERVED("STOCK_002", "이미 예약된 상품입니다.", 409, false),

    // Payment
    PAYMENT_FAILED("PAY_001", "결제에 실패했습니다.", 400, true),                 // 시스템 신호
    INVALID_PAYMENT_COMBINATION("PAY_002", "유효하지 않은 결제 수단 조합입니다.", 400, false),
    INSUFFICIENT_POINTS("PAY_003", "포인트 잔액이 부족합니다.", 400, false),
    AMOUNT_MISMATCH("PAY_004", "결제 금액 합계가 상품 가격과 일치하지 않습니다.", 400, false),

    // Idempotency
    DUPLICATE_REQUEST("IDEM_001", "중복된 요청입니다.", 409, false),

    // System (진짜 알람)
    REDIS_UNAVAILABLE("SYS_001", "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503, true),
    SERVICE_OVERLOADED("SYS_002", "요청이 많아 잠시 후 다시 시도해 주세요.", 503, true),
    INTERNAL_ERROR("SYS_999", "서버 오류가 발생했습니다.", 500, true);

    private final String code;
    private final String message;
    private final int httpStatus;
    /** true 면 WARN/ERROR 로 알람. false 면 DEBUG (정상 비즈니스 거절). */
    private final boolean alarmWorthy;
}
