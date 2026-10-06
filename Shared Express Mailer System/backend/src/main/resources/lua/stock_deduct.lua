-- KEYS[1]=stock:available:{sid}  KEYS[2]=stock:hold:{orderNo}  KEYS[3]=stock:init:{sid}
-- KEYS[4]=stock:reconcile:{sid}  KEYS[5]=borrow:open:{uid}     KEYS[6]=borrow:daily:{uid}:{yyyyMMdd}
-- ARGV[1]=预扣凭据JSON  ARGV[2]=凭据TTL秒  ARGV[3]=open上限  ARGV[4]=daily上限  ARGV[5]=daily TTL秒
if redis.call('EXISTS', KEYS[3]) == 1 then return -1 end   -- 计数初始化中 → 5102
if redis.call('EXISTS', KEYS[4]) == 1 then return -2 end   -- 对账中 → 5103，客户端指数退避重试
local avail = tonumber(redis.call('GET', KEYS[1]) or '-1')
if avail == -1 then return -1 end                          -- 计数未初始化 → 5102
if tonumber(redis.call('GET', KEYS[5]) or '0') >= tonumber(ARGV[3]) then return -3 end  -- 在持袋数超限 → 4104
if tonumber(redis.call('GET', KEYS[6]) or '0') >= tonumber(ARGV[4]) then return -4 end  -- 取袋频次超限 → 4109
if avail < 1 then return 0 end                             -- 库存不足 → 4101
-- 以下四步在同一脚本内原子完成；前面任一检查未通过则整体不落账
redis.call('DECR', KEYS[1])                                -- 库存扣减
redis.call('SETEX', KEYS[2], ARGV[2], ARGV[1])             -- 预扣凭据
redis.call('INCR', KEYS[5])                                -- 在持袋数计数
redis.call('INCR', KEYS[6])                                -- 取袋日频次
redis.call('EXPIRE', KEYS[6], ARGV[5])
redis.call('EXPIRE', KEYS[5], 1209600)                     -- 计数 TTL 14 天滑动续期（对账重建时 SETEX 显式续期）
return 1
