package com.sems.module.order.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.Constants;
import com.sems.common.Result;
import com.sems.module.order.dto.BorrowDto;
import com.sems.module.order.dto.BorrowVo;
import com.sems.module.order.dto.OrderDetailVo;
import com.sems.module.order.dto.ShippingDto;
import com.sems.module.order.service.BorrowService;
import com.sems.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 取袋动作族接口（见《系统设计说明书》5.2「共享快递袋服务模块（M2）」与 6.1 资源根约定：
 * {@code /borrows} 为登记的唯一路径例外——申领、提交寄件、撤回以动作语义独立成根，便于 Nginx 单独限流）。
 *
 * <p>权限列均为「登录用户/本人」：垂直越权由 {@code @RequireRole("USER")}（等级制，见契约 4.4）拦截，
 * 「本人」的水平越权在 Service 层比对 {@code user_id} 强制校验（设计书 4.4）。
 *
 * <p>幂等：写接口统一从请求头 {@code clientRequestId} 取幂等 ID（设计书 9.6），
 * 缺失或格式错由 Service 抛 3001；这里 {@code required = false} 是为了让错误码统一为业务口径，
 * 而不是抛出 Spring 的缺头异常。
 */
@RestController
@RequestMapping("/api/v1/borrows")
@RequireRole("USER")
public class BorrowController {

    private final BorrowService borrowService;

    public BorrowController(BorrowService borrowService) {
        this.borrowService = borrowService;
    }

    /**
     * 申领取袋（POST /api/v1/borrows，设计书 5.2）。
     *
     * <p>完整两阶段流程见 {@link BorrowService#borrow}（契约 9 的 12 步）。
     */
    @PostMapping
    @AuditLog(module = "共享快递袋服务", action = "申领")
    public Result<BorrowVo> borrow(@Valid @RequestBody BorrowDto dto,
                                   @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                   String clientRequestId) {
        return Result.ok(borrowService.borrow(dto, clientRequestId));
    }

    /**
     * 提交寄件信息（POST /api/v1/borrows/{orderNo}/shipping，设计书 5.2、转移 #3）。
     */
    @PostMapping("/{orderNo}/shipping")
    @AuditLog(module = "共享快递袋服务", action = "提交寄件")
    public Result<OrderDetailVo> shipping(@PathVariable String orderNo,
                                          @Valid @RequestBody ShippingDto dto,
                                          @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                          String clientRequestId) {
        return Result.ok(borrowService.shipping(orderNo, dto, clientRequestId));
    }

    /**
     * 撤回申领（DELETE /api/v1/borrows/{orderNo}，设计书 5.2、7.2 R1）。
     *
     * <p>仅置订单已取消 + 删预扣凭据；不改袋状态、不减在持袋数（袋由站管按 #4/#5 收回闭环）。
     */
    @DeleteMapping("/{orderNo}")
    @AuditLog(module = "共享快递袋服务", action = "撤回")
    public Result<Void> withdraw(@PathVariable String orderNo,
                                 @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                 String clientRequestId) {
        borrowService.withdraw(orderNo, clientRequestId);
        return Result.ok();
    }
}
