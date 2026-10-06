-- R0 同步补偿脚本（见《系统设计说明书》7.2「R0 同步补偿」）：
--   KEYS[1]=stock:available:{sid}  KEYS[2]=stock:hold:{orderNo}  KEYS[3]=borrow:open:{uid}
--   ARGV[1]=保留未用（与 stock_deduct.lua 的参数位对齐，便于统一封装调用）
-- 以预扣凭据存在性作幂等凭据，防止重复补偿：凭据不存在说明库存从未预扣或此前已补偿过，直接返回 0。
if redis.call('EXISTS', KEYS[2]) == 0 then return 0 end
redis.call('DEL', KEYS[2])                                 -- 删除预扣凭据
redis.call('INCR', KEYS[1])                                -- 库存回补 +1
local n = tonumber(redis.call('GET', KEYS[3]) or '0')
if n > 0 then redis.call('DECR', KEYS[3]) end              -- 在持袋数 −1（已为 0 则不再减，避免计数为负）
return 1
