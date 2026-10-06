package com.sems.module.bag.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.Constants;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.bag.dto.CreateInventoryCheckDto;
import com.sems.module.bag.dto.InventoryCheckQuery;
import com.sems.module.bag.dto.InventoryCheckVo;
import com.sems.module.bag.dto.VoidInventoryCheckDto;
import com.sems.module.bag.service.InventoryCheckService;
import com.sems.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存盘点接口（快递袋管理模块 M4；见《系统设计说明书》5.4、3.4.4 与需求 FR-M4-17）。
 *
 * <p>端点与权限（逐条对应 5.4 接口表）：
 * <ul>
 *   <li>{@code GET /api/v1/inventory-checks}：盘点单分页查询（按站点/状态/时间筛选，
 *       含袋级差异清单），站点/平台管理员，本站站点管理员限本站；</li>
 *   <li>{@code POST /api/v1/inventory-checks}：提交盘点单（账面/实际/差异自动计算），
 *       本站站点管理员（平台管理员可全站代操作）；</li>
 *   <li>{@code POST /api/v1/inventory-checks/{id}/void}：作废盘点单（仅「待修正」可作废，
 *       否则 4105；盘点单不可删除，作废后可重盘）。</li>
 * </ul>
 *
 * <p>垂直越权由类级 {@code @RequireRole("STATION_ADMIN")} 拦截；「本站」水平越权在 Service 层落实（4.4）。
 * 写接口从请求头取 {@code clientRequestId} 幂等 ID，缺失或格式错由 Service 抛 3001（契约 10）。
 */
@RestController
@RequestMapping("/api/v1/inventory-checks")
@RequireRole(Constants.ROLE_STATION_ADMIN)
public class InventoryCheckController {

    private final InventoryCheckService inventoryCheckService;

    public InventoryCheckController(InventoryCheckService inventoryCheckService) {
        this.inventoryCheckService = inventoryCheckService;
    }

    /** 盘点单分页查询（含袋级差异清单）。 */
    @GetMapping
    public Result<PageResult<InventoryCheckVo>> page(InventoryCheckQuery query) {
        return Result.ok(inventoryCheckService.page(query));
    }

    /** 提交盘点单（账面数以 DB 实时统计为准，差异与袋级差异清单自动生成，状态=待修正）。 */
    @PostMapping
    @AuditLog(module = "快递袋管理", action = "提交盘点单")
    public Result<InventoryCheckVo> submit(@Valid @RequestBody CreateInventoryCheckDto dto,
                                           @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                           String clientRequestId) {
        return Result.ok(inventoryCheckService.submit(dto, clientRequestId));
    }

    /** 作废盘点单（仅「待修正」可作废；记录作废原因/操作人/时间，作废后可重新盘点）。 */
    @PostMapping("/{id}/void")
    @AuditLog(module = "快递袋管理", action = "作废盘点单")
    public Result<InventoryCheckVo> voidCheck(@PathVariable Long id,
                                              @Valid @RequestBody VoidInventoryCheckDto dto,
                                              @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                              String clientRequestId) {
        return Result.ok(inventoryCheckService.voidCheck(id, dto, clientRequestId));
    }
}
