-- ============================================================
-- 共享快递袋系统（Shared Express Mailer System）初始化数据脚本 V2
-- 目标：MySQL 8.0 / InnoDB / utf8mb4 / utf8mb4_0900_ai_ci
-- 用途：预置三类角色、预置平台管理员账号（首次登录强制修改密码）与示例站点；
--       与《数据库设计说明书》5.2、《需求规格说明书》FR-M1-11 保持同步。
-- 执行顺序：容器首次初始化时按文件名升序自动导入（V1__init_schema.sql → 本文件），
--           目录挂载见 deploy/docker/docker-compose.yml；已有数据卷不会重跑，
--           脚本变更后请用 scripts\db-reset.cmd 重建。
-- 初始口令：预置平台管理员手机号 13800000000，初始口令 Sems@2026，
--           must_change_password=1（首次登录强制修改密码后方可开展业务）。
-- 可重复执行：全部 INSERT ... ON DUPLICATE KEY UPDATE（MySQL 8.0.19+ 行别名写法），依赖 V1 中已有唯一键
--           （uk_role_code / uk_user_phone / uk_user_role_user_id_role_id / uk_station_name）；
--           不使用固定主键 ID，用户与角色关联通过 SELECT 现取 id；
--           重复执行会把预置数据恢复为上述初始值（含口令重置与再次强制改密）。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- sys_role：三类角色（权限点走注解，此处只登记角色码与名称） ----------
INSERT INTO `sys_role` (`role_code`, `role_name`, `description`) VALUES
  ('USER',           '普通用户',   '取用/归还、订单查询、个人中心'),
  ('STATION_ADMIN',  '站点管理员', '出入库、库存盘点、消毒登记、维修报废'),
  ('PLATFORM_ADMIN', '平台管理员', '用户/订单/快递袋/站点管理、数据统计') AS new
ON DUPLICATE KEY UPDATE
  `role_name`   = new.`role_name`,
  `description` = new.`description`;

-- ---------- sys_user：预置平台管理员账号（首次登录强制改密） ----------
INSERT INTO `sys_user` (`phone`, `password`, `nickname`, `status`, `must_change_password`)
VALUES ('13800000000',
        '$2a$10$bkRLjkG8TNMkqsGH/OnM3umF4asemVR9Eg2p2A400nLDuF6vbSMsq',
        '平台管理员',
        'ENABLED',
        1) AS new
ON DUPLICATE KEY UPDATE
  `password`             = new.`password`,
  `nickname`             = new.`nickname`,
  `status`               = new.`status`,
  `must_change_password` = new.`must_change_password`;

-- ---------- sys_user_role：管理员↔平台管理员角色关联（SELECT 现取 id，不写死主键） ----------
INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.`id`, r.`id`
  FROM `sys_user` u, `sys_role` r
 WHERE u.`phone` = '13800000000'
   AND r.`role_code` = 'PLATFORM_ADMIN'
ON DUPLICATE KEY UPDATE `user_id` = `user_id`;

-- ---------- station：示例站点（含开放时段与库存预警阈值） ----------
-- open_time 按设计书 3.5 只作展示与引导，不做硬校验，故直接写中文文本；
-- 缺省留空会让用户端「附近站点」列表少一行信息，演示时显得残缺。
INSERT INTO `station` (`name`, `address`, `open_time`, `warn_threshold`, `status`) VALUES
  ('校园东区快递服务中心', '广州市从化区广从南路 548 号·东区', '每日 08:00–21:00', 20, 'ENABLED'),
  ('校园西区快递服务中心', '广州市从化区广从南路 548 号·西区', '每日 08:30–20:30', 15, 'ENABLED') AS new
ON DUPLICATE KEY UPDATE
  `address`        = new.`address`,
  `open_time`      = new.`open_time`,
  `warn_threshold` = new.`warn_threshold`,
  `status`         = new.`status`;
