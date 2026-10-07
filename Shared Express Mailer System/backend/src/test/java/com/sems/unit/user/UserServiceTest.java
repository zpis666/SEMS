package com.sems.unit.user;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.user.dto.UserInfoVo;
import com.sems.module.user.dto.UpdateStatusDto;
import com.sems.module.user.dto.CreateUserDto;
import com.sems.module.user.dto.ChangePasswordDto;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.mapper.SysRoleMapper;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.module.user.mapper.SysUserRoleMapper;
import com.sems.module.user.service.UserService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final SysRoleMapper roles = mock(SysRoleMapper.class);
    private final SysUserRoleMapper links = mock(SysUserRoleMapper.class);
    private final StationMapper stations = mock(StationMapper.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserService service = new UserService(users, roles, links, stations, encoder, redis);

    @AfterEach
    void clear() { AuthContext.clear(); }

    @Test
    void getMeUsesAuthenticatedIdentityAndReturnsMaskedPhoneWithoutCredential() {
        LoginUser current = new LoginUser();
        current.setUserId(23L);
        AuthContext.set(current);
        SysUser user = new SysUser();
        user.setId(23L);
        user.setPhone("13800001234");
        user.setPassword("$2a$10$secret-hash");
        user.setNickname("本人");
        user.setStatus(Constants.STATUS_ENABLED);
        user.setMustChangePassword(1);
        when(users.selectById(23L)).thenReturn(user);
        when(links.selectList(any())).thenReturn(List.of());

        UserInfoVo result = service.getMe();

        assertEquals(23L, result.getId());
        assertEquals("138****1234", result.getPhone());
        assertEquals("本人", result.getNickname());
        assertNull(result.getRole());
        assertTrue(result.isMustChangePassword());
        assertFalse(result.toString().contains("secret-hash"));
        verify(users).selectById(23L);
        verify(users, never()).selectById(999L);
    }

    @Test
    void getMeFailsClosedWhenAuthenticatedUserWasDeleted() {
        LoginUser current = new LoginUser();
        current.setUserId(99L);
        AuthContext.set(current);
        when(users.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, service::getMe);
        assertEquals(3001, ex.getErrorCode().getCode());
        verifyNoInteractions(links, roles);
    }

    @Test
    void platformAdminCannotDisableOwnAccount() {
        LoginUser current = new LoginUser();
        current.setUserId(11L);
        current.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(current);
        SysUser self = new SysUser();
        self.setId(11L);
        when(users.selectById(11L)).thenReturn(self);
        UpdateStatusDto request = new UpdateStatusDto();
        request.setStatus(Constants.STATUS_DISABLED);

        BizException ex = assertThrows(BizException.class, () -> service.updateStatus(11L, request));

        assertEquals(3001, ex.getErrorCode().getCode());
        verify(users, never()).update(any(), any());
        verify(users, never()).incrementTokenVersion(anyLong());
    }

    @Test
    void duplicatePhoneIsRejectedBeforeRoleOrCredentialWrites() {
        CreateUserDto dto = new CreateUserDto();
        dto.setPhone(" 13800001234 ");
        dto.setRoles(List.of(Constants.ROLE_USER));
        when(users.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> service.createUser(dto));

        assertEquals(3001, ex.getErrorCode().getCode());
        verifyNoInteractions(roles, links, stations);
        verify(users, never()).insert(any(SysUser.class));
    }

    @Test
    void accountCreationRequiresAtLeastOneRole() {
        CreateUserDto dto = new CreateUserDto();
        dto.setPhone("13800001234");
        dto.setRoles(List.of());
        when(users.selectCount(any())).thenReturn(0L);

        BizException ex = assertThrows(BizException.class, () -> service.createUser(dto));

        assertEquals(3001, ex.getErrorCode().getCode());
        verifyNoInteractions(links, stations);
        verify(users, never()).insert(any(SysUser.class));
    }

    @Test
    void wrongCurrentPasswordCannotChangeCredentialsOrInvalidateSessions() {
        LoginUser current = new LoginUser();
        current.setUserId(21L);
        AuthContext.set(current);
        SysUser user = new SysUser();
        user.setId(21L);
        user.setPassword(encoder.encode("old-password"));
        when(users.selectById(21L)).thenReturn(user);
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("wrong-password");
        dto.setNewPassword("new-password");

        BizException ex = assertThrows(BizException.class, () -> service.changePassword(dto));

        assertEquals(3001, ex.getErrorCode().getCode());
        verify(users, never()).update(any(SysUser.class), any());
        verify(users, never()).incrementTokenVersion(anyLong());
        verifyNoInteractions(redis);
    }

    @Test
    void successfulPasswordChangeClearsForcedChangeAndRevokesExistingTokens() {
        LoginUser current = new LoginUser();
        current.setUserId(22L);
        AuthContext.set(current);
        SysUser user = new SysUser();
        user.setId(22L);
        user.setPhone("15900009999");
        user.setPassword(encoder.encode("old-password"));
        user.setMustChangePassword(1);
        when(users.selectById(22L)).thenReturn(user);
        when(links.selectList(any())).thenReturn(List.of());
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("old-password");
        dto.setNewPassword("new-password");

        UserInfoVo result = service.changePassword(dto);

        assertFalse(result.isMustChangePassword());
        assertFalse(result.toString().contains("password"));
        assertEquals("159****9999", result.getPhone());
        verify(users).update(isNull(SysUser.class), any());
        verify(users).incrementTokenVersion(22L);
        verify(redis).delete("auth:userver:22");
    }

    @Test
    void disablingAnotherAccountRevokesItsTokenVersionButEnablingDoesNot() {
        LoginUser current = new LoginUser();
        current.setUserId(1L);
        current.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(current);
        SysUser target = new SysUser();
        target.setId(2L);
        target.setPhone("13700001111");
        target.setStatus(Constants.STATUS_ENABLED);
        target.setMustChangePassword(0);
        SysUser disabledSnapshot = new SysUser();
        disabledSnapshot.setId(2L);
        disabledSnapshot.setPhone("13700001111");
        disabledSnapshot.setStatus(Constants.STATUS_DISABLED);
        disabledSnapshot.setMustChangePassword(0);
        when(users.selectById(2L)).thenReturn(target, disabledSnapshot, disabledSnapshot, target);
        when(links.selectList(any())).thenReturn(List.of());

        UpdateStatusDto disable = new UpdateStatusDto();
        disable.setStatus(Constants.STATUS_DISABLED);
        var disabled = service.updateStatus(2L, disable);
        assertEquals(Constants.STATUS_DISABLED, disabled.getStatus());
        verify(users).incrementTokenVersion(2L);
        verify(redis).delete("auth:userver:2");

        clearInvocations(users, redis, links, roles);
        UpdateStatusDto enable = new UpdateStatusDto();
        enable.setStatus(Constants.STATUS_ENABLED);
        var enabled = service.updateStatus(2L, enable);
        assertEquals(Constants.STATUS_ENABLED, enabled.getStatus());
        verify(users, never()).incrementTokenVersion(anyLong());
        verifyNoInteractions(redis);
    }
}
