-- =============================================================================
-- 초기 데이터 (개발/테스트용)
-- ddl-auto=update 와 함께 실행되며, 재부팅 시 중복 INSERT 가 발생하지 않도록 IGNORE 사용.
-- =============================================================================

-- 한정 특가 상품 1개 (10개 재고)
INSERT IGNORE INTO product (id, name, description, price, check_in_at, check_out_at, total_stock, open_at)
VALUES (1, '한정 특가 디럭스 트윈', '도심 호텔 1박 한정 패키지 - 시즌 오프 특가',
        50000, '2026-05-01 15:00:00', '2026-05-02 11:00:00', 10, '2026-04-29 00:00:00');

INSERT IGNORE INTO product_stock (product_id, remaining) VALUES (1, 10);

-- 테스트용 사용자 포인트
INSERT IGNORE INTO user_point (user_id, balance, version) VALUES
    (1, 30000, 0),
    (2, 10000, 0),
    (3, 0, 0),
    (100, 100000, 0),
    (200, 50000, 0);
