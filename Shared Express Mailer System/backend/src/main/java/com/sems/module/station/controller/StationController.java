package com.sems.module.station.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.station.dto.CreateStationDto;
import com.sems.module.station.dto.NearbyStationVo;
import com.sems.module.station.dto.RecycleBinVo;
import com.sems.module.station.dto.RecycleConfirmDto;
import com.sems.module.station.dto.RecycleConfirmVo;
import com.sems.module.station.dto.StationQuery;
import com.sems.module.station.dto.StationStatusDto;
import com.sems.module.station.dto.StationStockVo;
import com.sems.module.station.dto.StationVo;
import com.sems.module.station.dto.UpdateStationDto;
import com.sems.module.station.service.RecycleService;
import com.sems.module.station.service.StationService;
import com.sems.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 共享站点管理接口（M3，路径与权限严格照《系统设计说明书》5.3 的两个接口小节与 5.2 的 nearby 一行）。
 *
 * <p>端点清单（不得增删）：
 * <ul>
 *   <li>GET /stations 站点分页查询（平台管理员）；</li>
 *   <li>POST /stations 新增站点（平台管理员）；</li>
 *   <li>PUT /stations/{id} 编辑站点（平台管理员）；</li>
 *   <li>PATCH /stations/{id}/status 停用/启用（平台管理员）；</li>
 *   <li>GET /stations/{id}/stock 站点库存视图（平台管理员 / 本站站点管理员）；</li>
 *   <li>GET /stations/stock-warning 库存预警列表（平台管理员）；</li>
 *   <li>GET /stations/{id}/recycle-bin 回收箱待确认清单（本站站点管理员 / 平台管理员）；</li>
 *   <li>POST /stations/{id}/recycle-confirm 扫码回收确认（本站站点管理员 / 平台管理员）；</li>
 *   <li>GET /stations/nearby 站点列表，供取袋与归还引导（登录用户）。</li>
 * </ul>
 *
 * <p><b>两层权限分工：</b>垂直越权由 {@code @RequireRole}（等级制）拦截；
 * 水平越权（「本站」）在 Service 层强制比对（见 4.4 与契约 6）。
 * 写接口统一从请求头取 {@code clientRequestId} 幂等 ID（UUID 格式，缺失或格式错抛 3001），
 * 审计按 7.6：新增/编辑/停用站点与回收确认强制留痕。
 */
@RestController
@RequestMapping("/api/v1/stations")
public class StationController {

    private final StationService stationService;
    private final RecycleService recycleService;

    public StationController(StationService stationService, RecycleService recycleService) {
        this.stationService = stationService;
        this.recycleService = recycleService;
    }

    /** 站点分页查询（平台管理员；5.3 /stations）。 */
    @GetMapping
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    public Result<PageResult<StationVo>> page(StationQuery query) {
        return Result.ok(stationService.page(query));
    }

    /** 新增站点（平台管理员；5.3 POST /stations）。 */
    @PostMapping
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "站点管理", action = "新增站点")
    public Result<StationVo> create(@Valid @RequestBody CreateStationDto dto,
                                    @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                    String clientRequestId) {
        return Result.ok(stationService.create(dto, requireClientRequestId(clientRequestId)));
    }

    /** 编辑站点（平台管理员；5.3 PUT /stations/{id}）。 */
    @PutMapping("/{id}")
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "站点管理", action = "编辑站点")
    public Result<StationVo> update(@PathVariable Long id,
                                    @Valid @RequestBody UpdateStationDto dto,
                                    @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                    String clientRequestId) {
        return Result.ok(stationService.update(id, dto, requireClientRequestId(clientRequestId)));
    }

    /** 停用/启用站点（平台管理员；5.3 PATCH /stations/{id}/status）。 */
    @PatchMapping("/{id}/status")
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "站点管理", action = "停用站点")
    public Result<StationVo> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody StationStatusDto dto,
                                          @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                          String clientRequestId) {
        return Result.ok(stationService.updateStatus(id, dto, requireClientRequestId(clientRequestId)));
    }

    /** 站点各状态库存实时数（平台管理员 / 本站站点管理员；5.3 /stations/{id}/stock）。 */
    @GetMapping("/{id}/stock")
    @RequireRole(Constants.ROLE_STATION_ADMIN)
    public Result<StationStockVo> stock(@PathVariable Long id) {
        return Result.ok(stationService.stock(id));
    }

    /** 低于预警阈值的站点列表（平台管理员；5.3 /stations/stock-warning）。 */
    @GetMapping("/stock-warning")
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    public Result<List<StationStockVo>> stockWarning() {
        return Result.ok(stationService.stockWarning());
    }

    /** 回收箱待确认清单（本站站点管理员 / 平台管理员；5.3 /stations/{id}/recycle-bin）。 */
    @GetMapping("/{id}/recycle-bin")
    @RequireRole(Constants.ROLE_STATION_ADMIN)
    public Result<List<RecycleBinVo>> recycleBin(@PathVariable Long id) {
        return Result.ok(recycleService.recycleBin(id));
    }

    /** 扫码回收确认（本站站点管理员 / 平台管理员；5.3 /stations/{id}/recycle-confirm）。 */
    @PostMapping("/{id}/recycle-confirm")
    @RequireRole(Constants.ROLE_STATION_ADMIN)
    @AuditLog(module = "站点管理", action = "回收确认")
    public Result<RecycleConfirmVo> recycleConfirm(@PathVariable Long id,
                                                   @Valid @RequestBody RecycleConfirmDto dto,
                                                   @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                   String clientRequestId) {
        return Result.ok(recycleService.recycleConfirm(id, dto, requireClientRequestId(clientRequestId)));
    }

    /** 站点列表（登录用户；5.2 /stations/nearby，过滤停用站，供取袋与归还引导）。 */
    @GetMapping("/nearby")
    @RequireRole(Constants.ROLE_USER)
    public Result<List<NearbyStationVo>> nearby() {
        return Result.ok(stationService.nearby());
    }

    /** 写接口幂等 ID：缺失或非 UUID 格式一律抛 3001（契约第 4 节）。 */
    private static String requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
        return clientRequestId;
    }
}
