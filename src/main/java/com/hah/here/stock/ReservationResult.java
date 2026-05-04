package com.hah.here.stock;

public record ReservationResult(ReservationStatus status, int remaining) {

    public static ReservationResult success(int remaining) {
        return new ReservationResult(ReservationStatus.SUCCESS, remaining);
    }

    public static ReservationResult alreadyReserved() {
        return new ReservationResult(ReservationStatus.ALREADY_RESERVED, 0);
    }

    public static ReservationResult soldOut() {
        return new ReservationResult(ReservationStatus.SOLD_OUT, 0);
    }

    public static ReservationResult keyMissing() {
        return new ReservationResult(ReservationStatus.KEY_MISSING, 0);
    }

    public static ReservationResult redisDown() {
        return new ReservationResult(ReservationStatus.REDIS_DOWN, 0);
    }

    public boolean isSuccess() {
        return status == ReservationStatus.SUCCESS;
    }
}
