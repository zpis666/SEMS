-- ============================================================
-- 共享快递袋系统（Shared Express Mailer System）建表脚本 V1
-- 目标：MySQL 8.0 / InnoDB / utf8mb4 / utf8mb4_0900_ai_ci
-- 来源：《数据库设计说明书》V1.0 第 4 章
-- 约定：全部 CREATE TABLE IF NOT EXISTS，可重复执行；不含 DROP
-- 说明：本文件由数据库设计规格生成，请勿手工修改；
--       如需变更请改《数据库设计说明书》后重新生成。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- sys_user：用户账号表（三角色共用） ----------
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户ID（自增主键）',
  `phone` varchar(11) NOT NULL COMMENT '登录手机号，出参脱敏',
  `password` varchar(60) NOT NULL COMMENT '密码BCrypt摘要（cost≥10）',
  `nickname` varchar(32) NOT NULL COMMENT '昵称（注册必填）',
  `status` varchar(20) NOT NULL DEFAULT 'ENABLED' COMMENT '账号状态：ENABLED启用/DISABLED禁用',
  `station_id` bigint unsigned COMMENT '绑定站点ID，仅站点管理员非空',
  `token_version` int unsigned NOT NULL DEFAULT 0 COMMENT '令牌版本，自增即全量吊销',
  `must_change_password` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '须强制改密（预置/重置账号）',
  `last_login_at` datetime(3) COMMENT '最后登录时间',
  `last_login_ip` varchar(45) COMMENT '最后登录IP（兼容IPv6）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（注册时间）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，随修改自动刷新',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone` (`phone`),
  KEY `fk_user_station_id` (`station_id`),
  KEY `idx_user_status_created_at` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账号表（三角色共用）';

-- ---------- sys_role：角色表（三行预置，权限点走注解） ----------
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '角色ID（自增主键）',
  `role_code` varchar(20) NOT NULL COMMENT '角色码，预置三类固定值',
  `role_name` varchar(32) NOT NULL COMMENT '角色名称（普通用户/站点管理员/平台管理员）',
  `description` varchar(255) COMMENT '角色说明',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，随修改自动刷新',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表（三行预置，权限点走注解）';

-- ---------- sys_user_role：用户与角色关联表 ----------
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '关联ID（自增主键）',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `role_id` bigint unsigned NOT NULL COMMENT '角色ID',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '授予时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role_user_id_role_id` (`user_id`, `role_id`),
  KEY `idx_user_role_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与角色关联表';

-- ---------- station：共享站点（袋收发与回收服务点） ----------
CREATE TABLE IF NOT EXISTS `station` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '站点ID，自增主键',
  `name` varchar(50) NOT NULL COMMENT '站点名称，全平台唯一',
  `address` varchar(200) NOT NULL COMMENT '站点地址（展示与就近引导）',
  `open_time` varchar(50) COMMENT '开放时段展示文本，不做硬校验',
  `warn_threshold` int unsigned NOT NULL DEFAULT 0 COMMENT '库存预警阈值（低于则预警；0=不预警）',
  `status` varchar(20) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED启用/DISABLED停用',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_name` (`name`),
  KEY `idx_station_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='共享站点（袋收发与回收服务点）';

-- ---------- stock_record：站点出入库流水（只插入） ----------
CREATE TABLE IF NOT EXISTS `stock_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '流水ID，自增主键',
  `station_id` bigint unsigned NOT NULL COMMENT '发生出入库的站点ID',
  `bag_code` varchar(20) NOT NULL COMMENT '快递袋编码，关联 bag.bag_code',
  `type` varchar(20) NOT NULL COMMENT '类型：IN入库/OUT出库/SCRAP_OUT报废出库',
  `operator_id` bigint unsigned NOT NULL COMMENT '操作人用户ID（代操作记平台管理员）',
  `remark` varchar(200) COMMENT '备注（差异单号、报废说明等）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '出入库发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_stock_record_bag_created` (`bag_code`, `created_at`),
  KEY `idx_stock_record_station_created` (`station_id`, `created_at`),
  KEY `fk_stock_record_operator_id` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站点出入库流水（只插入）';

-- ---------- bag：快递袋档案（全生命周期底账） ----------
CREATE TABLE IF NOT EXISTS `bag` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID（自增）',
  `bag_code` varchar(20) NOT NULL COMMENT '袋编码：SJB-规格-年月-流水-校验',
  `spec` varchar(20) NOT NULL COMMENT '规格（1位码值嵌入袋码，如 L）',
  `color` varchar(20) NOT NULL COMMENT '颜色（如 黄色）',
  `capacity` varchar(50) NOT NULL COMMENT '容量（如 33×25×5cm）',
  `station_id` bigint unsigned NOT NULL COMMENT '当前存放站点（随实物转移；建袋写登记站）',
  `status` varchar(20) NOT NULL DEFAULT 'INACTIVE' COMMENT '袋状态（11 态枚举，初始 INACTIVE）',
  `use_count` int unsigned NOT NULL DEFAULT 0 COMMENT '复用次数（唯一递增点：回收确认）',
  `max_use_count` int unsigned NOT NULL DEFAULT 50 COMMENT '复用上限（默认 50，超限拒绝上架）',
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本（条件 UPDATE 防重领）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建档时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（更新时自动刷新）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bag_code` (`bag_code`),
  KEY `idx_bag_station_status` (`station_id`, `status`),
  KEY `idx_bag_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快递袋档案（全生命周期底账）';

-- ---------- bag_transfer_log：快递袋状态流转日志（全程追溯） ----------
CREATE TABLE IF NOT EXISTS `bag_transfer_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID（自增，写入有序）',
  `bag_code` varchar(20) NOT NULL COMMENT '快递袋编码（追溯关联，不设外键）',
  `from_status` varchar(20) NOT NULL COMMENT '流转前状态（11 态枚举码）',
  `to_status` varchar(20) NOT NULL COMMENT '流转后状态（11 态枚举码）',
  `event` varchar(20) NOT NULL COMMENT '触发事件（BagEvent 枚举码）',
  `operator_id` bigint unsigned NOT NULL COMMENT '操作人用户ID（登录用户或管理员）',
  `station_id` bigint unsigned NOT NULL COMMENT '操作站点（事件发生地）',
  `proxy_flag` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '平台管理员代操作：0 否，1 是',
  `ref_check_no` varchar(32) COMMENT '盘点差异单号，#21 修正必填',
  `remark` varchar(255) COMMENT '备注（修正原因/找回说明等）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '流转时间（写入即发生时间）',
  PRIMARY KEY (`id`),
  KEY `idx_btl_bag_code_created_at` (`bag_code`, `created_at`),
  KEY `idx_btl_ref_check_no` (`ref_check_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快递袋状态流转日志（全程追溯）';

-- ---------- bag_order：快递订单（取用闭环主记录） ----------
CREATE TABLE IF NOT EXISTS `bag_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID（自增）',
  `order_no` varchar(32) NOT NULL COMMENT '业务订单号（对外唯一，系统生成）',
  `client_request_id` varchar(64) NOT NULL COMMENT '客户端幂等ID（申领落库防重）',
  `user_id` bigint unsigned NOT NULL COMMENT '申领用户ID（订单归属人）',
  `bag_code` varchar(20) NOT NULL COMMENT '快递袋编码（SJB 定长码）',
  `station_id` bigint unsigned NOT NULL COMMENT '取袋站点（申领发生站点）',
  `return_station_id` bigint unsigned COMMENT '归还站点（标记归还时写入）',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING_SEND' COMMENT '订单状态（6 态枚举，初始 PENDING_SEND）',
  `express_company` varchar(30) COMMENT '快递公司（寄件时写入）',
  `tracking_no` varchar(20) COMMENT '运单号（8–20 位，寄件时写入）',
  `recipient_phone_mask` varchar(20) COMMENT '收件人手机号掩码（如 138****1234）',
  `recipient_phone_hash` varchar(80) COMMENT '收件人手机号HMAC哈希（含密钥版本）',
  `timeout_flag` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '超时标记（寄件 7 天未还置 1）',
  `timeout_time` datetime(3) COMMENT '超时标记时间（置 1 时写入）',
  `shipped_at` datetime(3) COMMENT '寄件提交时间（7/14 天基准）',
  `return_mark_at` datetime(3) COMMENT '标记归还时间（24h/72h 基准）',
  `return_remind_at` datetime(3) COMMENT '归还提醒已发时间（防重推）',
  `confirmed_at` datetime(3) COMMENT '回收确认时间（订单完成）',
  `cancelled_at` datetime(3) COMMENT '取消时间（撤回/超时/回收）',
  `lost_at` datetime(3) COMMENT '遗失确认时间（订单终态）',
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本（条件更新防并发）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申领建单时间（24h 取消基准）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（变更自动刷新）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_order_no` (`order_no`),
  UNIQUE KEY `uk_order_client_request_id` (`client_request_id`),
  UNIQUE KEY `uk_order_express_tracking` (`express_company`, `tracking_no`),
  KEY `idx_order_user_status` (`user_id`, `status`),
  KEY `idx_order_status_created_at` (`status`, `created_at`),
  KEY `idx_order_created_at` (`created_at`),
  KEY `idx_order_timeout` (`timeout_flag`, `created_at`),
  KEY `idx_order_bag_code` (`bag_code`),
  KEY `fk_order_station_id` (`station_id`),
  KEY `fk_order_return_station_id` (`return_station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快递订单（取用闭环主记录）';

-- ---------- liability_record：责任记录（遗失/在途遗失/超时） ----------
CREATE TABLE IF NOT EXISTS `liability_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID（自增）',
  `biz_type` varchar(20) NOT NULL COMMENT '业务类型（ORDER订单/ALLOCATION调拨）',
  `biz_no` varchar(32) NOT NULL COMMENT '业务单号（订单号或调拨单号）',
  `type` varchar(20) NOT NULL COMMENT '责任类型（遗失/在途遗失/超时）',
  `responsible_user_id` bigint unsigned COMMENT '责任用户（可空=责任待定）',
  `responsible_station_id` bigint unsigned COMMENT '责任站点（调拨在途遗失=源站）',
  `conclusion` varchar(255) COMMENT '处置结论（管理员裁决内容）',
  `decided_by` bigint unsigned COMMENT '裁决人（平台管理员ID）',
  `decided_at` datetime(3) COMMENT '裁决时间',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '责任状态（PENDING待认定/CONFIRMED已认定）',
  `note` varchar(255) COMMENT '注记（找回等追加说明）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（认定动作发生）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（注记追加刷新）',
  PRIMARY KEY (`id`),
  KEY `idx_liability_biz_no_type` (`biz_no`, `type`),
  KEY `fk_liability_responsible_user_id` (`responsible_user_id`),
  KEY `fk_liability_responsible_station_id` (`responsible_station_id`),
  KEY `fk_liability_decided_by` (`decided_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='责任记录（遗失/在途遗失/超时）';

-- ---------- stock_compensation_log：库存补偿日志（库存回补流水） ----------
CREATE TABLE IF NOT EXISTS `stock_compensation_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID（自增）',
  `client_request_id` varchar(64) NOT NULL COMMENT '申领请求幂等ID（防重复补偿）',
  `order_no` varchar(32) NOT NULL COMMENT '申领订单号（事务失败时未落库）',
  `station_id` bigint unsigned NOT NULL COMMENT '库存回补的站点ID',
  `reason` varchar(100) NOT NULL COMMENT '补偿原因（如申领落库失败）',
  `compensated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '补偿发生时间（业务时刻）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录写入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scl_client_request_id` (`client_request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存补偿日志（库存回补流水）';

-- ---------- disinfect_record：消毒批次记录 ----------
CREATE TABLE IF NOT EXISTS `disinfect_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `station_id` bigint unsigned NOT NULL COMMENT '消毒站点 ID',
  `batch_no` varchar(32) NOT NULL COMMENT '消毒批次号（全局唯一）',
  `method` varchar(20) NOT NULL COMMENT '消毒方式：紫外线/臭氧/高温/喷洒',
  `start_time` datetime(3) NOT NULL COMMENT '消毒开始时间',
  `end_time` datetime(3) NOT NULL COMMENT '消毒结束时间',
  `operator_id` bigint unsigned NOT NULL COMMENT '消毒登记操作人 ID',
  `result` varchar(20) NOT NULL COMMENT '批次汇总：合格/部分不合格/不合格',
  `bag_count` int unsigned NOT NULL DEFAULT 0 COMMENT '本批消毒袋数',
  `pass_count` int unsigned NOT NULL DEFAULT 0 COMMENT '合格袋数',
  `fail_count` int unsigned NOT NULL DEFAULT 0 COMMENT '不合格袋数',
  `remark` varchar(255) COMMENT '备注',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_batch_no` (`batch_no`),
  KEY `idx_dr_station_time` (`station_id`, `created_at`),
  KEY `fk_disinfect_record_operator_id` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消毒批次记录';

-- ---------- disinfect_detail：消毒批次袋码明细 ----------
CREATE TABLE IF NOT EXISTS `disinfect_detail` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `disinfect_id` bigint unsigned NOT NULL COMMENT '所属消毒批次 ID',
  `bag_code` varchar(20) NOT NULL COMMENT '快递袋编码',
  `result` varchar(20) NOT NULL COMMENT '消毒结果：合格/不合格',
  `defect_desc` varchar(255) COMMENT '不合格缺陷说明（合格为空）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dd_disinfect_bag` (`disinfect_id`, `bag_code`),
  KEY `idx_dd_bag_time` (`bag_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消毒批次袋码明细';

-- ---------- repair_record：维修记录（登记/完成） ----------
CREATE TABLE IF NOT EXISTS `repair_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `bag_code` varchar(20) NOT NULL COMMENT '快递袋编码',
  `reporter_id` bigint unsigned NOT NULL COMMENT '报修人（登记破损的管理员）',
  `description` varchar(255) NOT NULL COMMENT '破损描述（报修时登记）',
  `start_time` datetime(3) NOT NULL COMMENT '维修开始时间（转维修时点）',
  `end_time` datetime(3) COMMENT '维修完成时间（未完成为空）',
  `result` varchar(20) COMMENT '维修结果：修复/无法修复',
  `operator_id` bigint unsigned COMMENT '维修完成操作人（未完成为空）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_rr_bag_time` (`bag_code`, `created_at`),
  KEY `fk_repair_record_reporter_id` (`reporter_id`),
  KEY `fk_repair_record_operator_id` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='维修记录（登记/完成）';

-- ---------- scrap_record：快递袋报废记录 ----------
CREATE TABLE IF NOT EXISTS `scrap_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `bag_code` varchar(20) NOT NULL COMMENT '快递袋编码',
  `station_id` bigint unsigned NOT NULL COMMENT '报废站点 ID（袋当时所在站）',
  `reason` varchar(255) NOT NULL COMMENT '报废原因（严重破损/超限/无法修复等）',
  `scrap_time` datetime(3) NOT NULL COMMENT '报废时间（登记时点）',
  `operator_id` bigint unsigned NOT NULL COMMENT '报废登记操作人 ID',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_sr_bag_time` (`bag_code`, `created_at`),
  KEY `idx_sr_station_time` (`station_id`, `created_at`),
  KEY `fk_scrap_record_operator_id` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快递袋报废记录';

-- ---------- inventory_check：库存盘点单 ----------
CREATE TABLE IF NOT EXISTS `inventory_check` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `check_no` varchar(32) NOT NULL COMMENT '盘点单号（全局唯一）',
  `station_id` bigint unsigned NOT NULL COMMENT '盘点站点ID',
  `check_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '实盘执行时间（业务时间）',
  `expect_json` json NOT NULL COMMENT '账面各状态袋数快照（JSON）',
  `actual_json` json NOT NULL COMMENT '实盘各状态袋数快照（JSON）',
  `diff_bags_json` json NOT NULL COMMENT '袋级差异清单[{bagCode,diffType}]',
  `diff_count` int unsigned NOT NULL DEFAULT 0 COMMENT '差异袋数（差异清单条数）',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING_FIX' COMMENT '状态：待修正/已闭环/作废',
  `operator_id` bigint unsigned NOT NULL COMMENT '盘点人（提交账号）ID',
  `closed_at` datetime(3) COMMENT '闭环时间（差异修正完毕）',
  `voided_at` datetime(3) COMMENT '作废时间',
  `voided_by` bigint unsigned COMMENT '作废操作人ID',
  `void_reason` varchar(255) COMMENT '作废原因（重盘说明）',
  `remark` varchar(255) COMMENT '备注',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（自动刷新）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_check_check_no` (`check_no`),
  KEY `idx_inventory_check_station_created` (`station_id`, `created_at`),
  KEY `idx_inventory_check_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存盘点单';

-- ---------- allocation_order：站点间调拨单 ----------
CREATE TABLE IF NOT EXISTS `allocation_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `allocation_no` varchar(32) NOT NULL COMMENT '调拨单号（业务唯一编号）',
  `from_station_id` bigint unsigned NOT NULL COMMENT '源站点ID（调出方）',
  `to_station_id` bigint unsigned NOT NULL COMMENT '目标站点ID（调入方）',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING_DISPATCH' COMMENT '状态：待出库/在途/已完成/异常/已取消/已终止',
  `dispatch_at` datetime(3) COMMENT '首只出库时间（超时起算）',
  `completed_at` datetime(3) COMMENT '全部核收完成时间',
  `abnormal_at` datetime(3) COMMENT '置异常时间（在途遗失/部分核收）',
  `cancelled_at` datetime(3) COMMENT '取消或异常后终止时间',
  `cancel_reason` varchar(255) COMMENT '取消或异常后终止原因（取消仅限待出库）',
  `terminated_by` bigint unsigned COMMENT '异常后终止操作人ID（平台管理员）',
  `created_by` bigint unsigned NOT NULL COMMENT '创建人用户ID（平台管理员）',
  `remark` varchar(255) COMMENT '备注',
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（自动刷新）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_allocation_order_allocation_no` (`allocation_no`),
  KEY `idx_allocation_order_status_dispatch_at` (`status`, `dispatch_at`),
  KEY `idx_allocation_order_from_station_status` (`from_station_id`, `status`),
  KEY `idx_allocation_order_to_station_status` (`to_station_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站点间调拨单';

-- ---------- allocation_detail：调拨单袋级明细 ----------
CREATE TABLE IF NOT EXISTS `allocation_detail` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `allocation_id` bigint unsigned NOT NULL COMMENT '所属调拨单ID',
  `bag_code` varchar(20) NOT NULL COMMENT '快递袋编码',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING_DISPATCH' COMMENT '状态：待出库/已出库/已核收/已剔除/已遗失',
  `dispatch_at` datetime(3) COMMENT '该袋出库时间',
  `receive_at` datetime(3) COMMENT '该袋核收时间',
  `removed_at` datetime(3) COMMENT '剔除（解锁）时间',
  `remark` varchar(255) COMMENT '备注',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（建单即锁定）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（自动刷新）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_allocation_detail_allocation_id_bag_code` (`allocation_id`, `bag_code`),
  KEY `idx_allocation_detail_bag_code` (`bag_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调拨单袋级明细';

-- ---------- audit_log：操作审计日志 ----------
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `operator_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作人ID(0=匿名或未知)',
  `operator_role` varchar(20) NOT NULL DEFAULT '' COMMENT '操作人角色快照(空=匿名)',
  `ip` varchar(45) NOT NULL DEFAULT '' COMMENT '客户端IP(IPv4/IPv6)',
  `ua` varchar(255) NOT NULL DEFAULT '' COMMENT '客户端User-Agent(超长截断)',
  `module` varchar(50) NOT NULL DEFAULT '' COMMENT '业务模块(如:快递袋管理)',
  `action` varchar(50) NOT NULL DEFAULT '' COMMENT '业务动作(如:消毒登记)',
  `request_uri` varchar(255) NOT NULL DEFAULT '' COMMENT '请求URI(如/api/v1/bags)',
  `params_masked` varchar(4096) NOT NULL DEFAULT '' COMMENT '脱敏后入参JSON(超长截断)',
  `result` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '结果:1成功/0失败',
  `error_code` int unsigned NOT NULL DEFAULT 0 COMMENT '错误码(0=成功,见6.4)',
  `cost_ms` int unsigned NOT NULL DEFAULT 0 COMMENT '处理耗时(毫秒)',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_created_at` (`created_at`),
  KEY `idx_audit_operator_created` (`operator_id`, `created_at`),
  KEY `idx_audit_module_created` (`module`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志';

-- ---------- login_log：登录日志(成功/失败与原因) ----------
CREATE TABLE IF NOT EXISTS `login_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户ID(0=账号不存在)',
  `phone_mask` varchar(20) NOT NULL DEFAULT '' COMMENT '手机号掩码(如138****1234)',
  `result` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '登录结果:1成功/0失败',
  `fail_reason` varchar(20) NOT NULL DEFAULT '' COMMENT '失败原因(密码错/不存在/锁定/禁用/验证码)',
  `ip` varchar(45) NOT NULL DEFAULT '' COMMENT '客户端IP(IPv4/IPv6)',
  `ua` varchar(255) NOT NULL DEFAULT '' COMMENT '客户端User-Agent(超长截断)',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_login_created_at` (`created_at`),
  KEY `idx_login_user_created` (`user_id`, `created_at`),
  KEY `idx_login_result_created` (`result`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志(成功/失败与原因)';

-- ---------- stats_daily：日统计汇总(可选优化) ----------
CREATE TABLE IF NOT EXISTS `stats_daily` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `date` date NOT NULL COMMENT '统计日期',
  `station_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '站点ID(0=全平台汇总)',
  `orders_borrow` int unsigned NOT NULL DEFAULT 0 COMMENT '当日取袋(申领)订单数',
  `orders_return` int unsigned NOT NULL DEFAULT 0 COMMENT '当日归还确认订单数',
  `turnover_rate` double NOT NULL DEFAULT 0 COMMENT '当日周转率(归还数/在循环均数)',
  `stock_json` json NOT NULL COMMENT '库存分布快照(各状态袋数)',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '汇总生成时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '重算更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stats_date_station` (`date`, `station_id`),
  KEY `idx_stats_station_date` (`station_id`, `date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日统计汇总(可选优化)';

-- ---------- sys_metric：系统指标计数表（承载审计丢弃计数等运行期指标） ----------
CREATE TABLE IF NOT EXISTS `sys_metric` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID（自增）',
  `metric_key` varchar(64) NOT NULL COMMENT '指标键，如 audit_drop_count',
  `stat_date` date NOT NULL COMMENT '统计日期（按天累计）',
  `metric_value` bigint unsigned NOT NULL DEFAULT 0 COMMENT '指标值（累加计数）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_metric_key_date` (`metric_key`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统指标计数表（承载审计丢弃计数等运行期指标）';

-- ============================================================
-- 合计 21 张表
-- ============================================================