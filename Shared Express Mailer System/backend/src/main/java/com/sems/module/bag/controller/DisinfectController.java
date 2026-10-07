package com.sems.module.bag.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.Constants;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.bag.dto.CreateDisinfectDto;
import com.sems.module.bag.dto.DisinfectCreateVo;
import com.sems.module.bag.dto.DisinfectQuery;
import com.sems.module.bag.dto.DisinfectVo;
import com.sems.module.bag.service.DisinfectService;
import com.sems.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消毒登记接口（快递袋管理模块 M4；见《系统设计说明书》5.4 与 4.3 角色矩阵）。
 *
 * <p>端点与权限（逐条对应 5.4 接口表）：
 * <ul>
 *   <li>{@code GET /api/v1/disinfections}：消毒批次分页查询，站点/平台管理员，
 *       含明细逐袋结果，本站站点管理员限本站；</li>
 *   <li>{@code POST /api/v1/disinfections}：消毒批次登记，本站站点管理员（平台管理员可全站代操作），
 *       袋码明细 ≤200 只/批（{@code sems.batch.disinfect-max} 的批量上限例外值），
 *       返回统一批量信封（批次头 + items 逐袋结果，元素固定 bagCode/success/code/message），
 *       单只失败不影响其余。</li>
 * </ul>
 *
 * <p>垂直越权由类级 {@code @RequireRole("STATION_ADMIN")}（等级制：平台管理员等级更高自然放行）拦截；
 * 「本站」水平越权与代操作判定在 Service 层落实（4.4）。写接口从请求头取 {@code clientRequestId}
 * 幂等 ID，缺失或格式错由 Service 抛 3001（契约 10）。
 */
@RestController
@RequestMapping("/api/v1/disinfections")
@RequireRole(Constants.ROLE_STATION_ADMIN)
public class DisinfectController {

    private final DisinfectService disinfectService;

    public DisinfectController(DisinfectService disinfectService) {
        this.disinfectService = disinfectService;
    }

    /** 消毒批次分页查询（按站点/时间/结果筛选，含批次明细逐袋结果）。 */
    @GetMapping
    public Result<PageResult<DisinfectVo>> page(DisinfectQuery query) {
        return Result.ok(disinfectService.page(query));
    }

    /** 消毒批次登记（合格→空闲 #9，不合格→维修中 #10；返回统一批量信封：批次头 + items 逐袋结果）。 */
    @PostMapping
    @AuditLog(module = "快递袋管理", action = "消毒登记")
    public Result<DisinfectCreateVo> create(@Valid @RequestBody CreateDisinfectDto dto,
                                            @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                            String clientRequestId) {
        return Result.ok(disinfectService.register(dto, clientRequestId));
    }
}
