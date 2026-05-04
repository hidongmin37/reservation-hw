-- =============================================================================
-- Schema definition for here_db
-- =============================================================================

-- 거래 단위 (재고는 별도 테이블 분리, 핫 row 최소화)
CREATE TABLE IF NOT EXISTS product (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(255)  NOT NULL,
    description  VARCHAR(1000),
    price        DECIMAL(12, 0) NOT NULL,
    check_in_at  DATETIME      NOT NULL,
    check_out_at DATETIME      NOT NULL,
    total_stock  INT           NOT NULL,
    open_at      DATETIME      NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 재고 (Redis가 운영 진실, DB는 영속 백업 + fallback용)
CREATE TABLE IF NOT EXISTS product_stock (
    product_id   BIGINT NOT NULL PRIMARY KEY,
    remaining INT    NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 예약 (= 결제 완료된 booking 결과)
CREATE TABLE IF NOT EXISTS reservation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT         NOT NULL,
    product_id         BIGINT         NOT NULL,
    total_amount    DECIMAL(12, 0) NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    idempotency_key VARCHAR(64)    NOT NULL UNIQUE,
    created_at      DATETIME       NOT NULL,
    updated_at      DATETIME       NOT NULL,
    INDEX idx_reservation_status_created (status, created_at)  -- sweeper용
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 결제 (수단별 row, 복합결제는 한 reservation에 N rows)
CREATE TABLE IF NOT EXISTS payment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id    BIGINT         NOT NULL,
    method            VARCHAR(16)    NOT NULL,
    amount            DECIMAL(12, 0) NOT NULL,
    status            VARCHAR(16)    NOT NULL,
    pg_transaction_id VARCHAR(64),
    created_at        DATETIME       NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 사용자 포인트 (사용자별 row 분산, 낙관적 락)
CREATE TABLE IF NOT EXISTS user_point (
    user_id BIGINT         NOT NULL PRIMARY KEY,
    balance DECIMAL(12, 0) NOT NULL,
    version BIGINT         NOT NULL DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 멱등성 영속 안전망 (Redis 장애 중에도 응답 재현 보장)
-- 정상 모드: complete 시 DB 우선 + Redis best-effort.
-- Redis 장애: claim/complete/release 모두 DB 사용.
CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key VARCHAR(64) NOT NULL PRIMARY KEY,
    response_json   TEXT,
    status          VARCHAR(16) NOT NULL,    -- INFLIGHT / COMPLETED
    created_at      DATETIME    NOT NULL,
    completed_at    DATETIME,
    INDEX idx_idempotency_record_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 1유저-1상품 hold (DB Fallback 모드의 최종 안전망)
-- 정상 흐름은 Redis entered: ZSET 으로 1u1p 보장.
-- Redis 장애 중 같은 사용자가 *서로 다른 idempotency-key* 로 동시 진입하는 race 를
-- DB UNIQUE 제약으로 막는다. reservation 의 라이프사이클(PENDING/CONFIRMED) 동안 잔존하고
-- confirm/fail 시 정리, 누수된 건은 sweeper 가 stale 로 회수.
CREATE TABLE IF NOT EXISTS user_product_hold (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT   NOT NULL,
    product_id BIGINT   NOT NULL,
    held_at    DATETIME NOT NULL,
    UNIQUE KEY uk_user_product (user_id, product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
