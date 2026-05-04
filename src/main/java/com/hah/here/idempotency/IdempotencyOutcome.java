package com.hah.here.idempotency;

public record IdempotencyOutcome(Type type, String cachedJson) {

    public enum Type { FIRST, IN_FLIGHT, CACHED }

    public static IdempotencyOutcome first() {
        return new IdempotencyOutcome(Type.FIRST, null);
    }

    public static IdempotencyOutcome inFlight() {
        return new IdempotencyOutcome(Type.IN_FLIGHT, null);
    }

    public static IdempotencyOutcome cached(String json) {
        return new IdempotencyOutcome(Type.CACHED, json);
    }

    public boolean isFirst() {
        return type == Type.FIRST;
    }

    public boolean isInFlight() {
        return type == Type.IN_FLIGHT;
    }

    public boolean isCached() {
        return type == Type.CACHED;
    }
}
