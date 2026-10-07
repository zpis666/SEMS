package com.sems.unit.controller;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.Result;
import com.sems.common.util.JsonUtils;
import com.sems.infra.IdempotentService;
import com.sems.module.auth.controller.AuthController;
import com.sems.module.auth.dto.CaptchaVo;
import com.sems.module.auth.dto.LoginDto;
import com.sems.module.auth.dto.LoginVo;
import com.sems.module.auth.dto.RegisterDto;
import com.sems.module.auth.service.AuthService;
import com.sems.module.auth.service.CaptchaService;
import com.sems.module.user.controller.UserController;
import com.sems.module.user.dto.BindStationDto;
import com.sems.module.user.dto.ChangePasswordDto;
import com.sems.module.user.dto.CreateUserDto;
import com.sems.module.user.dto.UpdateProfileDto;
import com.sems.module.user.dto.UpdateRolesDto;
import com.sems.module.user.dto.UpdateStatusDto;
import com.sems.module.user.dto.UserDetailVo;
import com.sems.module.user.dto.UserInfoVo;
import com.sems.module.user.dto.UserQuery;
import com.sems.module.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUserControllerTest {

    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    @DisplayName("认证端点映射验证码、注册、登录 IP/UA、Bearer 续签与登出")
    void authControllerMapsRequestsAndResponses() {
        AuthService auth = mock(AuthService.class);
        CaptchaService captcha = mock(CaptchaService.class);
        AuthController controller = new AuthController(auth, captcha);
        CaptchaVo captchaVo = new CaptchaVo();
        LoginDto loginDto = new LoginDto();
        RegisterDto registerDto = new RegisterDto();
        LoginVo login = new LoginVo();
        when(captcha.generate()).thenReturn(captchaVo);
        when(auth.login(eq(loginDto), eq("198.51.100.8"), eq("test-agent"))).thenReturn(login);
        when(auth.refresh("refresh-token")).thenReturn(login);

        assertSame(captchaVo, controller.captcha().getData());
        Result<Void> registered = controller.register(registerDto);
        assertEquals(0, registered.getCode());
        assertNull(registered.getData());
        verify(auth).register(registerDto);

        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.addHeader("X-Forwarded-For", "198.51.100.8, 10.0.0.1");
        forwarded.addHeader("User-Agent", "test-agent");
        assertSame(login, controller.login(loginDto, forwarded).getData());
        verify(auth).login(loginDto, "198.51.100.8", "test-agent");

        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("203.0.113.4");
        remote.addHeader("X-Forwarded-For", "   ");
        remote.addHeader("User-Agent", "fallback");
        controller.login(loginDto, remote);
        verify(auth).login(loginDto, "203.0.113.4", "fallback");

        MockHttpServletRequest authenticated = bearer("Bearer refresh-token");
        assertSame(login, controller.refresh(authenticated, REQUEST_ID).getData());
        assertNull(controller.logout(authenticated, REQUEST_ID).getData());
        verify(auth).refresh("refresh-token");
        verify(auth).logout("refresh-token");
    }

    @Test
    @DisplayName("续签与登出对缺失幂等 ID 或非 Bearer 请求 fail-closed")
    void authControllerRejectsInvalidHeadersBeforeServiceCall() {
        AuthService auth = mock(AuthService.class);
        AuthController controller = new AuthController(auth, mock(CaptchaService.class));
        BizException missingId = assertThrows(BizException.class,
                () -> controller.refresh(bearer("Bearer token"), null));
        assertEquals(ErrorCode.PARAM_INVALID, missingId.getErrorCode());

        BizException malformedBearer = assertThrows(BizException.class,
                () -> controller.refresh(bearer("Basic token"), REQUEST_ID));
        assertEquals(ErrorCode.UNAUTHENTICATED, malformedBearer.getErrorCode());

        BizException emptyBearer = assertThrows(BizException.class,
                () -> controller.logout(bearer("Bearer   "), REQUEST_ID));
        assertEquals(ErrorCode.UNAUTHENTICATED, emptyBearer.getErrorCode());
        verify(auth, never()).refresh(anyString());
        verify(auth, never()).logout(anyString());
    }

    @Test
    @DisplayName("用户端与平台管理写入走接口级幂等并传递对应 DTO/ID")
    void userControllerDelegatesQueriesAndIdempotentWrites() {
        UserService service = mock(UserService.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        doNothing().when(idempotency).markProcessing(anyString(), anyString());
        doNothing().when(idempotency).markCompleted(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        UserController controller = new UserController(service, idempotency);
        UserInfoVo user = new UserInfoVo();
        UserDetailVo detail = new UserDetailVo();
        UserQuery query = new UserQuery();
        UpdateProfileDto profileDto = new UpdateProfileDto();
        ChangePasswordDto passwordDto = new ChangePasswordDto();
        CreateUserDto createDto = new CreateUserDto();
        UpdateRolesDto rolesDto = new UpdateRolesDto();
        BindStationDto stationDto = new BindStationDto();
        UpdateStatusDto statusDto = new UpdateStatusDto();
        when(service.getMe()).thenReturn(user);
        when(service.updateProfile(profileDto)).thenReturn(user);
        when(service.changePassword(passwordDto)).thenReturn(user);
        when(service.createUser(createDto)).thenReturn(detail);
        when(service.updateRoles(9L, rolesDto)).thenReturn(detail);
        when(service.updateStation(9L, stationDto)).thenReturn(detail);
        when(service.pageUsers(query)).thenReturn(com.sems.common.PageResult.of(java.util.List.of(detail), 1, 10));
        when(service.updateStatus(9L, statusDto)).thenReturn(detail);
        when(service.resetPassword(9L)).thenReturn(user);

        assertSame(user, controller.getMe().getData());
        assertSame(user, controller.updateMe(profileDto, REQUEST_ID).getData());
        assertSame(user, controller.changePassword(passwordDto, REQUEST_ID).getData());
        assertSame(detail, controller.createUser(createDto, REQUEST_ID).getData());
        assertSame(detail, controller.updateRoles(9L, rolesDto, REQUEST_ID).getData());
        assertSame(detail, controller.updateStation(9L, stationDto, REQUEST_ID).getData());
        assertEquals(1, controller.pageUsers(query).getData().getTotal());
        assertSame(detail, controller.updateStatus(9L, statusDto, REQUEST_ID).getData());
        assertSame(user, controller.resetPassword(9L, REQUEST_ID).getData());
        verify(service).updateRoles(9L, rolesDto);
        verify(service).updateStation(9L, stationDto);
        verify(service).resetPassword(9L);
        verify(idempotency).markCompleted(eq("POST /users/{id}/password-reset"), eq(REQUEST_ID),
                eq(JsonUtils.toJson(user)));
    }

    @Test
    @DisplayName("用户写接口拒绝非法幂等头；已完成结果回放不重复调用 Service")
    void userIdempotencyGuardsInvalidAndReplayedRequests() {
        UserService service = mock(UserService.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        UserController controller = new UserController(service, idempotency);
        UpdateProfileDto dto = new UpdateProfileDto();
        BizException invalid = assertThrows(BizException.class, () -> controller.updateMe(dto, "bad-id"));
        assertEquals(ErrorCode.PARAM_INVALID, invalid.getErrorCode());
        verify(service, never()).updateProfile(dto);

        UserInfoVo replay = new UserInfoVo();
        when(idempotency.findCompleted("PATCH /users/me", REQUEST_ID)).thenReturn(JsonUtils.toJson(replay));
        assertEquals(0, controller.updateMe(dto, REQUEST_ID).getCode());
        verify(service, never()).updateProfile(dto);
        verify(idempotency, never()).markProcessing("PATCH /users/me", REQUEST_ID);
    }

    private static MockHttpServletRequest bearer(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }
}
