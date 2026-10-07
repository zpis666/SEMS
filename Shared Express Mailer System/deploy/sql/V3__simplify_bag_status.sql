-- 快递袋正常循环精简为四态；升级前停止后端，备份数据库。
-- 历史流转日志与订单状态原样保留，不重写审计凭证。
ALTER TABLE bag MODIFY COLUMN status varchar(20) NOT NULL DEFAULT 'IDLE'
  COMMENT '袋状态（8 态枚举：IDLE/ALLOCATED/IN_USE/TO_DISINFECT/TRANSFERRING/REPAIRING/LOST/SCRAPPED）';
START TRANSACTION;
UPDATE bag SET status = CASE
  WHEN status = 'RETURNING' THEN 'IN_USE'
  WHEN use_count >= max_use_count THEN 'TO_DISINFECT'
  ELSE 'IDLE' END,
  version = version + 1
WHERE status IN ('INACTIVE', 'REUSABLE', 'RETURNING');
COMMIT;
-- 重启后端前清除各站点 stock:available:* 与 stock:sentinel:* 缓存，使系统按 DB 空闲袋数重建。
-- 已达复用上限的旧可复用袋转待消毒，须登记报废，不允许重新上架。
