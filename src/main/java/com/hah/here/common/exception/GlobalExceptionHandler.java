package com.hah.here.common.exception;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외는 *알람 가치* 별로 로그 레벨 분기.
     *  - 정상 거절(SOLD_OUT, ALREADY_RESERVED 등): DEBUG — 부하 시 로그 폭증 + I/O 부담 방지
     *  - 시스템 신호(PAYMENT_FAILED, REDIS_UNAVAILABLE 등): WARN — 알람 대상
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode ec = e.getErrorCode();
        if (ec.isAlarmWorthy()) {
            log.warn("비즈니스 예외: {} - {}", ec.getCode(), e.getMessage());
        } else {
            log.debug("정상 거절: {} - {}", ec.getCode(), e.getMessage());
        }
        return ResponseEntity.status(ec.getHttpStatus())
                .body(new ErrorResponse(ec.getCode(), e.getMessage()));
    }

    /**
     * Bulkhead 포화 — 시스템 보호 신호. 클라이언트는 잠시 후 재시도.
     * checkout/booking 의 동시 진입 한계 초과 시 발생.
     */
    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ErrorResponse> handleBulkheadFull(BulkheadFullException e) {
        ErrorCode ec = ErrorCode.SERVICE_OVERLOADED;
        log.warn("Bulkhead 포화: {} - {}", ec.getCode(), e.getMessage());
        return ResponseEntity.status(ec.getHttpStatus())
                .body(new ErrorResponse(ec.getCode(), ec.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALID_001", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        ErrorCode ec = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse(ec.getCode(), ec.getMessage()));
    }
}
