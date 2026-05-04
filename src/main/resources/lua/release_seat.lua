-- 보상: 자리 해제 (Phase B 결제 실패 시, 또는 sweeper 호출)
-- KEYS[1] = stock:{productId}
-- KEYS[2] = entered:{productId}    (ZSET)
-- ARGV[1] = userId
--
-- 반환:
--   0   이미 해제됨 (idempotent no-op)
--   1   해제 성공

if redis.call('ZSCORE', KEYS[2], ARGV[1]) == false then
  return 0
end

redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[1])
return 1
