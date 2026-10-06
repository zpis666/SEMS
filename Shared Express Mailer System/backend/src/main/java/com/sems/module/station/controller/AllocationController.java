package com.sems.module.station.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.Constants;
import com.sems.common.PageResult;
import com.sems.common.BatchResult;
import com.sems.common.Result;
import com.sems.module.station.dto.AllocationCancelDto;
import com.sems.module.station.dto.AllocationDetailVo;
import com.sems.module.station.dto.AllocationDispatchDto;
import com.sems.module.station.dto.AllocationLostDto;
import com.sems.module.station.dto.AllocationQuery;
import com.sems.module.station.dto.AllocationReceiveDto;
import com.sems.module.station.dto.AllocationRemoveDto;
import com.sems.module.station.dto.AllocationVo;
import com.sems.module.station.dto.CreateAllocationDto;
import com.sems.module.station.service.AllocationService;
import com.sems.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 调拨接口（M3 共享站点管理；见《系统设计说明书》5.3 调拨端点表，前缀统一 /api/v1）。
 *
 * <p>权限分两层：垂直越权由 {@link RequireRole} 注解拦截（等级制）；
 * 「本站」等水平越权由 {@code AllocationService} 在 Service 层校验（见 4.4）。
 * 所有写接口必须携带 {@code clientRequestId} 幂等头（UUID，见 9.6），由 Service 统一校验与回放；
 * 批量端点返回逐袋结果明细 {@code [{bagCode, success, code, message}]}，单只失败不影响其余。
 *
 * <p>审计：创建/取消（该端点异常单据走终止，服务层补记「终止调拨单」动作）/出库/核收/在途遗失确认/剔除明细
 * 均标 {@link AuditLog}，与设计书 7.6 必审事件对应。
 */
@RestController
@RequestMapping("/api/v1/allocations")
public class AllocationController {

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    /** 调拨单分页查询（5.3）：站点管理员强制过滤「源站或目标站=本站」，平台管理员不过滤。 */
    @GetMapping
    @RequireRole("STATION_ADMIN")
    public Result<PageResult<AllocationVo>> page(AllocationQuery query) {
        return Result.ok(allocationService.page(query));
    }

    /** 调拨单详情（5.3）：含明细逐袋核收进度与在途超时标注；非源/目标站报 2002。 */
    @GetMapping("/{id}")
    @RequireRole("STATION_ADMIN")
    public Result<AllocationDetailVo> detail(@PathVariable Long id) {
        return Result.ok(allocationService.detail(id));
    }

    /** 创建调拨单（5.3）：平台管理员；建单即锁定明细袋（被锁袋在其它环节报 4110）。 */
    @PostMapping
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "共享站点管理", action = "创建调拨单")
    public Result<AllocationDetailVo> create(@Valid @RequestBody CreateAllocationDto dto,
                                             @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                             String clientRequestId) {
        return Result.ok(allocationService.create(dto, clientRequestId));
    }

    /**
     * 取消 / 终止调拨单（5.3 DELETE）：平台管理员。Service 按单据状态分派——
     * 待出库走取消、异常走终止，其余状态 4105。请求体可缺省（取消原因可空）。
     */
    @DeleteMapping("/{id}")
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "共享站点管理", action = "取消调拨单")
    public Result<AllocationDetailVo> cancelOrTerminate(@PathVariable Long id,
                                                        @Valid @RequestBody(required = false) AllocationCancelDto dto,
                                                        @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                        String clientRequestId) {
        return Result.ok(allocationService.cancelOrTerminate(id, dto, clientRequestId));
    }

    /** 调拨出库（5.3）：源站站点管理员（平台管理员可代操作）；逐袋返回结果明细。 */
    @PostMapping("/{id}/dispatch")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "共享站点管理", action = "调拨出库")
    public Result<BatchResult<AllocationService.BagResult>> dispatch(@PathVariable Long id,
                                                                     @Valid @RequestBody AllocationDispatchDto dto,
                                                                     @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                                     String clientRequestId) {
        return Result.ok(BatchResult.of(allocationService.dispatch(id, dto, clientRequestId),
                AllocationService.BagResult::success));
    }

    /** 调拨核收入库（5.3）：目标站站点管理员（平台管理员可代操作）；逐袋返回结果明细。 */
    @PostMapping("/{id}/receive")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "共享站点管理", action = "调拨核收")
    public Result<BatchResult<AllocationService.BagResult>> receive(@PathVariable Long id,
                                                                    @Valid @RequestBody AllocationReceiveDto dto,
                                                                    @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                                    String clientRequestId) {
        return Result.ok(BatchResult.of(allocationService.receive(id, dto, clientRequestId),
                AllocationService.BagResult::success));
    }

    /** 在途遗失确认（5.3）：平台管理员；袋→已遗失、单据置异常并写责任记录（责任主体为源站）。 */
    @PostMapping("/{id}/lost-confirm")
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "共享站点管理", action = "在途遗失确认")
    public Result<BatchResult<AllocationService.BagResult>> lostConfirm(@PathVariable Long id,
                                                                        @Valid @RequestBody AllocationLostDto dto,
                                                                        @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                                        String clientRequestId) {
        return Result.ok(BatchResult.of(allocationService.lostConfirm(id, dto, clientRequestId),
                AllocationService.BagResult::success));
    }

    /** 剔除调拨明细（5.3）：平台管理员；仅待出库/在途单据中的待出库且仍空闲的袋可剔除。 */
    @PostMapping("/{id}/remove-detail")
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "共享站点管理", action = "剔除明细")
    public Result<AllocationDetailVo> removeDetail(@PathVariable Long id,
                                                   @Valid @RequestBody AllocationRemoveDto dto,
                                                   @RequestHeader(value = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                   String clientRequestId) {
        return Result.ok(allocationService.removeDetail(id, dto, clientRequestId));
    }
}
