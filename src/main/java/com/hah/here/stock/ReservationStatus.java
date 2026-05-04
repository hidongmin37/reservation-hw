package com.hah.here.stock;

public enum ReservationStatus {
    SUCCESS,
    ALREADY_RESERVED,
    SOLD_OUT,
    /** Redis 키가 유실됨(연결은 정상). 호출자가 rebuild 후 retry. */
    KEY_MISSING,
    /** Redis 자체 장애 (Circuit Breaker fallback 신호). 호출자가 DB Fallback 으로 우회. */
    REDIS_DOWN
}
