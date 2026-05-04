-- Phase A: 자리 확보 (재고 차감 + 1user-1product dedup)
-- KEYS[1] = stock:{productId}     (counter)
-- KEYS[2] = entered:{productId}   (ZSET: member=userId, score=reservedAt millis)
-- ARGV[1] = userId
-- ARGV[2] = currentTimeMillis
--
-- 반환:
--   {-2}              KEY_MISSING (Redis 키 유실, 호출자가 fallback 으로 우회)
--   {-1}              ALREADY_RESERVED
--   {0}               SOLD_OUT
--   {1, remaining}    SUCCESS
--
-- entered 를 ZSET 으로 둔 이유: Sweeper 의 고아 자리 회수가 grace time(예: 5분)
--   지난 entry 만 검사하도록, score=reservedAt 으로 시각 기록이 필요.

if redis.call('ZSCORE', KEYS[2], ARGV[1]) ~= false then
  return {-1}
end

local stockRaw = redis.call('GET', KEYS[1])
if stockRaw == false then
  -- 키 부재: 매진과 구별. 호출자가 fallback 으로 우회.
  return {-2}
end

local stock = tonumber(stockRaw)
if not stock or stock <= 0 then
  return {0}
end

redis.call('DECR', KEYS[1])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
return {1, stock - 1}
