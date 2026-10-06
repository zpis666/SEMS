package com.sems.module.order.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.Constants;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.order.dto.LostConfirmDto;
import com.sems.module.order.dto.OrderDetailVo;
import com.sems.module.order.dto.OrderQuery;
import com.sems.module.order.dto.OrderVo;
import com.sems.module.order.dto.ReturnMarkDto;
import com.sems.module.order.service.OrderService;
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
 * 快递订单接口（见《系统设计说明书》5.2 用户侧与 5.5「快递订单管理模块（M5）」管理侧；
 * 资源根统一为 {@code /orders}，按权限与数据范围区分——6.1 资源根约定）。
 *
 * <p>权限划分：
 * <ul>
 *   <li>{@code /orders/mine}、{@code /orders/{orderNo}}、{@code return-mark}：登录用户，
 *       数据范围「本人」（Service 层强制 {@code user_id = 当前 uid}，设计书 4.4）；</li>
 *   <li>{@code /orders}、{@code /orders/timeout}、{@code lost-confirm}：平台管理员
 *       （{@code @RequireRole("PLATFORM_ADMIN")}，等级制仅平台管理员可过）。</li>
 * </ul>
 *
 * <p>写接口统一从请求头 {@code clientRequestId} 取幂等 ID（设计书 9.6）；
 * 关键动作（标记归还、确认遗失）打 {@code @AuditLog} 入审计（设计书 7.6）。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 本人订单分页查询（GET /api/v1/orders/mine，设计书 5.2）。
     *
     * <p>强制本人数据范围：查询条件不接受 userId，服务端取当前登录 uid（防水平越权）。
     */
    @GetMapping("/mine")
    @RequireRole("USER")
    public Result<PageResult<OrderVo>> mine(OrderQuery query) {
        return Result.ok(orderService.pageMine(query));
    }

    /**
     * 全量订单查询（GET /api/v1/orders，设计书 5.5；平台管理员，支持状态/站点/时间筛选）。
     */
    @GetMapping
    @RequireRole("PLATFORM_ADMIN")
    public Result<PageResult<OrderVo>> list(OrderQuery query) {
        return Result.ok(orderService.pageAll(query));
    }

    /**
     * 超时未归还订单列表（GET /api/v1/orders/timeout，设计书 5.5；{@code timeout_flag=1}）。
     */
    @GetMapping("/timeout")
    @RequireRole("PLATFORM_ADMIN")
    public Result<PageResult<OrderVo>> timeout(OrderQuery query) {
        return Result.ok(orderService.pageTimeout(query));
    }

    /**
     * 本人订单详情（GET /api/v1/orders/{orderNo}，设计书 5.2：校验归属，非本人报 2002）。
     *
     * <p>本接口是 2002 水平越权的典型入口（枚举他人订单号），按 7.6「越权拦截（2001/2002）
     * 必须入审计」标注 {@code @AuditLog}：切面按错误码把动作规范化为「越权尝试」，
     * 查询类接口不记录入参（{@code recordParams=false}）以控制审计体积。
     */
    @GetMapping("/{orderNo}")
    @RequireRole("USER")
    @AuditLog(module = "快递订单管理", action = "订单详情查询", recordParams = false)
    public Result<OrderDetailVo> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.detail(orderNo));
    }

    /**
     * 标记"已投放回收箱"（POST /api/v1/orders/{orderNo}/return-mark，设计书 5.2、3.2 #6）。
     *
     * <p>记录 {@code return_station_id}、订单转待回收确认并向站点推送回收待办。
     */
    @PostMapping("/{orderNo}/return-mark")
    @RequireRole("USER")
    @AuditLog(module = "快递订单管理", action = "标记归还")
    public Result<Void> returnMark(@PathVariable String orderNo,
                                   @Valid @RequestBody ReturnMarkDto dto,
                                   @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                   String clientRequestId) {
        orderService.returnMark(orderNo, dto, clientRequestId);
        return Result.ok();
    }

    /**
     * 确认遗失（POST /api/v1/orders/{orderNo}/lost-confirm，设计书 5.5、3.2 #18；平台管理员）。
     */
    @PostMapping("/{orderNo}/lost-confirm")
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "快递订单管理", action = "确认遗失")
    public Result<Void> lostConfirm(@PathVariable String orderNo,
                                    @Valid @RequestBody LostConfirmDto dto,
                                    @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                    String clientRequestId) {
        orderService.lostConfirm(orderNo, dto, clientRequestId);
        return Result.ok();
    }
}
