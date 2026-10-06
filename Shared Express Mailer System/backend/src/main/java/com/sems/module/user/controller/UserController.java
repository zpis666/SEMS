package com.sems.module.user.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.common.util.JsonUtils;
import com.sems.infra.IdempotentService;
import com.sems.module.user.dto.ChangePasswordDto;
import com.sems.module.user.dto.CreateUserDto;
import com.sems.module.user.dto.UpdateProfileDto;
import com.sems.module.user.dto.UpdateRolesDto;
import com.sems.module.user.dto.BindStationDto;
import com.sems.module.user.dto.UpdateStatusDto;
import com.sems.module.user.dto.UserDetailVo;
import com.sems.module.user.dto.UserInfoVo;
import com.sems.module.user.dto.UserQuery;
import com.sems.module.user.service.UserService;
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

import java.util.function.Supplier;

/**
 * 用户管理接口（M1，见《系统设计说明书》5.1 用户管理模块接口表；统一前缀 {@code /api/v1/users}）。
 *
 * <p>端点与权限严格照 5.1 表：{@code /users/me} 三个端点面向「登录用户」（三角色皆可，等级制
 * 鉴权下 {@code @RequireRole("USER")} 全部放行）；其余六个端点仅平台管理员。
 * 水平越权不适用于账号管理（平台管理员为全局数据范围）；本人接口的数据范围由 Service 层
 * 以 {@code AuthContext.userId()} 强制（4.4）。
 *
 * <p>写接口统一走接口级幂等（设计书 9.6 与契约模块版第 4 节范式）：{@code apiKey} 用
 * 「HTTP 方法 + 路径模板」；首答结果缓存 10 分钟，重放请求原样回放首次响应。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final IdempotentService idempotentService;

    public UserController(UserService userService, IdempotentService idempotentService) {
        this.userService = userService;
        this.idempotentService = idempotentService;
    }

    // ------------------------------------------------------------------
    // 登录用户（/users/me）
    // ------------------------------------------------------------------

    /** 个人信息（手机号脱敏），见 5.1。 */
    @GetMapping("/me")
    @RequireRole(Constants.ROLE_USER)
    public Result<UserInfoVo> getMe() {
        return Result.ok(userService.getMe());
    }

    /** 修改昵称等非敏感字段，见 5.1。 */
    @PatchMapping("/me")
    @RequireRole(Constants.ROLE_USER)
    @AuditLog(module = "用户管理", action = "修改个人信息")
    public Result<UserInfoVo> updateMe(
            @Valid @RequestBody UpdateProfileDto dto,
            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false) String clientRequestId) {
        return Result.ok(idempotentWrite("PATCH /users/me", clientRequestId, UserInfoVo.class,
                () -> userService.updateProfile(dto)));
    }

    /** 修改密码（校验旧密码；成功后 token_version+1，全部旧令牌失效），见 5.1。 */
    @PostMapping("/me/password")
    @RequireRole(Constants.ROLE_USER)
    @AuditLog(module = "用户管理", action = "修改密码")
    public Result<UserInfoVo> changePassword(
            @Valid @RequestBody ChangePasswordDto dto,
            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false) String clientRequestId) {
        return Result.ok(idempotentWrite("POST /users/me/password", clientRequestId, UserInfoVo.class,
                () -> userService.changePassword(dto)));
    }

    // ------------------------------------------------------------------
    // 平台管理员
    // ------------------------------------------------------------------

    /** 创建账号并授予角色（站点管理员须同时绑定站点），见 5.1。 */
    @PostMapping
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "用户管理", action = "创建账号")
    public Result<UserDetailVo> createUser(
            @Valid @RequestBody CreateUserDto dto,
            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false) String clientRequestId) {
        return Result.ok(idempotentWrite("POST /users", clientRequestId, UserDetailVo.class,
                () -> userService.createUser(dto)));
    }

    /** 调整角色（全量替换；触发 token_version+1），见 5.1。 */
    @PutMapping("/{id}/roles")
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "用户管理", action = "调整角色")
    public Result<UserDetailVo> updateRoles(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateRolesDto dto,
            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false) String clientRequestId) {
        return Result.ok(idempotentWrite("PUT /users/{id}/roles", clientRequestId, UserDetailVo.class,
                () -> userService.updateRoles(id, dto)));
    }

    /** 调整站点绑定（触发 token_version+1），见 5.1。 */
    @PutMapping("/{id}/station")
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "用户管理", action = "调整站点绑定")
    public Result<UserDetailVo> updateStation(
            @PathVariable("id") Long id,
            @Valid @RequestBody BindStationDto dto,
            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false) String clientRequestId) {
        return Result.ok(idempotentWrite("PUT /users/{id}/station", clientRequestId, UserDetailVo.class,
                () -> userService.updateStation(id, dto)));
    }

    /** 分页查询用户（手机号模糊 / 角色 / 状态筛选），见 5.1。 */
    @GetMapping
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    public Result<PageResult<UserDetailVo>> pageUsers(UserQuery query) {
        return Result.ok(userService.pageUsers(query));
    }

    /** 启用/禁用账号（禁用触发 token_version+1），见 5.1。 */
    @PatchMapping("/{id}/status")
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "用户管理", action = "启用/禁用账号")
    public Result<UserDetailVo> updateStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateStatusDto dto,
            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false) String clientRequestId) {
        return Result.ok(idempotentWrite("PATCH /users/{id}/status", clientRequestId, UserDetailVo.class,
                () -> userService.updateStatus(id, dto)));
    }

    /** 重置密码（置 must_change_password=1，触发 token_version+1），见 5.1。 */
    @PostMapping("/{id}/password-reset")
    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    @AuditLog(module = "用户管理", action = "重置密码")
    public Result<UserInfoVo> resetPassword(
            @PathVariable("id") Long id,
            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false) String clientRequestId) {
        return Result.ok(idempotentWrite("POST /users/{id}/password-reset", clientRequestId, UserInfoVo.class,
                () -> userService.resetPassword(id)));
    }

    // ------------------------------------------------------------------
    // 幂等包装
    // ------------------------------------------------------------------

    /**
     * 写接口接口级幂等（契约模块版第 4 节范式，全站统一，勿自创）：
     * 查首次结果 → 命中「处理中」提示稍后重试 → 命中「已完成」原样回放 → 否则占位后执行业务，
     * 成功改写为已完成；任何失败立即删除占位，保证客户端重试不被自己的占位挡死。
     *
     * <p>业务方法带 {@code @Transactional}：本包装在事务提交后才写「已完成」，
     * 不会出现「缓存已记成功、DB 实际回滚」的裂缝。
     *
     * @param apiKey          幂等键前缀：HTTP 方法 + 路径模板（如 {@code "POST /users"}）
     * @param clientRequestId 请求头 {@code clientRequestId}（UUID 格式，缺失/非法抛 3001）
     * @param resultType      首次结果的回放类型
     * @param action          业务动作
     */
    private <T> T idempotentWrite(String apiKey, String clientRequestId, Class<T> resultType, Supplier<T> action) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(apiKey, crid);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            T replayed = JsonUtils.parse(cached, resultType);
            if (replayed != null) {
                return replayed;
            }
        }
        idempotentService.markProcessing(apiKey, crid);
        try {
            T result = action.get();
            idempotentService.markCompleted(apiKey, crid, JsonUtils.toJson(result));
            return result;
        } catch (RuntimeException e) {
            idempotentService.clear(apiKey, crid);
            throw e;
        }
    }

    /** 幂等请求头校验（设计书 9.6：写接口必带 UUID 格式的 clientRequestId）。 */
    private String requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
        return clientRequestId;
    }
}
