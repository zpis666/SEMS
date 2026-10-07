package com.sems.unit.auth;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sems.common.*;
import com.sems.config.SemsProperties;
import com.sems.infra.*;
import com.sems.module.auth.dto.*;
import com.sems.module.auth.service.*;
import com.sems.module.user.entity.*;
import com.sems.module.user.mapper.*;
import com.sems.module.audit.entity.LoginLog;
import com.sems.module.audit.service.AuditLogService;
import com.sems.security.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** 认证失败锁定、人机校验、最小角色、会话轮换与注册原子性单元测试。 */
class AuthenticationServiceTest {
 @org.junit.jupiter.api.BeforeAll static void metadata(){com.sems.unit.support.MybatisTestMetadata.init();}
 SysUserMapper users=mock(SysUserMapper.class);SysRoleMapper roles=mock(SysRoleMapper.class);SysUserRoleMapper links=mock(SysUserRoleMapper.class);BCryptPasswordEncoder encoder=mock(BCryptPasswordEncoder.class);JwtService jwt=mock(JwtService.class);CaptchaService captcha=mock(CaptchaService.class);AuditLogService audit=mock(AuditLogService.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);SemsProperties props=new SemsProperties();RateLimitService rate=mock(RateLimitService.class);AuthService service;
 @BeforeEach void init(){props.getAuth().setLockMaxFails(5);props.getAuth().setLockMinutes(15);when(redis.opsForValue()).thenReturn(values);when(values.increment(anyString())).thenReturn(1L);service=new AuthService(users,roles,links,encoder,jwt,captcha,audit,redis,props,rate);}
 @AfterEach void cleanup(){AuthContext.clear();}
 SysUser account(){SysUser u=new SysUser();u.setId(1L);u.setPhone("13800000000");u.setNickname("测试用户");u.setPassword("密文");u.setStatus("ENABLED");u.setTokenVersion(2);u.setMustChangePassword(0);return u;}
 LoginDto login(){LoginDto d=new LoginDto();d.setPhone("13800000000");d.setPassword("测试密码");return d;}
 RegisterDto register(){RegisterDto d=new RegisterDto();d.setPhone("13800000000");d.setPassword("测试密码");d.setNickname("测试");d.setCaptchaId("图片1");d.setCaptchaCode("ABCD");return d;}
 @Test void registrationStoresHashAndOnlyOrdinaryRole(){when(encoder.encode(anyString())).thenReturn("密文");SysRole role=new SysRole();role.setId(3L);role.setRoleCode("USER");when(roles.selectOne(any(Wrapper.class))).thenReturn(role);doAnswer(i->{SysUser u=i.getArgument(0);u.setId(8L);return 1;}).when(users).insert(any(SysUser.class));service.register(register());verify(captcha).verifyAndConsume("图片1","ABCD");verify(users).insert(argThat((SysUser u)->u.getPassword().equals("密文")&&u.getMustChangePassword()==0));verify(links).insert(argThat((SysUserRole u)->u.getUserId()==8L&&u.getRoleId()==3L));}
 @Test void registrationRejectsExistingAndConcurrentDuplicate(){when(users.selectCount(any(Wrapper.class))).thenReturn(1L);assertEquals(ErrorCode.PARAM_INVALID,assertThrows(BizException.class,()->service.register(register())).getErrorCode());verify(users,never()).insert(any(SysUser.class));when(users.selectCount(any(Wrapper.class))).thenReturn(0L);doThrow(new DuplicateKeyException("重复手机号")).when(users).insert(any(SysUser.class));assertEquals(ErrorCode.PARAM_INVALID,assertThrows(BizException.class,()->service.register(register())).getErrorCode());}
 @Test void registrationCannotCreateRolelessAccount(){assertEquals(ErrorCode.SYSTEM_ERROR,assertThrows(BizException.class,()->service.register(register())).getErrorCode());verify(links,never()).insert(any(SysUserRole.class));}
 @Test void captchaFailureStopsDatabaseAccess(){doThrow(new BizException(ErrorCode.PARAM_INVALID)).when(captcha).verifyAndConsume(any(),any());assertThrows(BizException.class,()->service.register(register()));verifyNoInteractions(users,roles,links);}
 @Test void nonexistentAndWrongPasswordShareMessageAndLeaveMaskedAudit(){BizException missing=assertThrows(BizException.class,()->service.login(login(),"127.0.0.1","浏览器"));when(users.selectOne(any(Wrapper.class))).thenReturn(account());BizException wrong=assertThrows(BizException.class,()->service.login(login(),"127.0.0.1","浏览器"));assertEquals(missing.getMessage(),wrong.getMessage());verify(audit,times(2)).recordLogin(argThat(l->l.getResult()==0&&!l.getPhoneMask().contains("13800000000")));verify(jwt,never()).issue(any());}
 @Test void disabledAccountCannotLoginEvenWithCorrectPassword(){SysUser u=account();u.setStatus("DISABLED");when(users.selectOne(any(Wrapper.class))).thenReturn(u);assertEquals(ErrorCode.FORBIDDEN,assertThrows(BizException.class,()->service.login(login(),null,null)).getErrorCode());verify(encoder,never()).matches(any(),any());}
 @Test void lockedAccountDoesNotCheckPassword(){when(values.get(RedisKeys.loginFail("13800000000"))).thenReturn("99");assertEquals(ErrorCode.ACCOUNT_LOCKED,assertThrows(BizException.class,()->service.login(login(),null,null)).getErrorCode());verify(users,never()).selectOne(any(Wrapper.class));}
 @Test void failingAtLimitRecordsLockMemory(){when(values.increment(anyString())).thenReturn(99L);assertEquals(ErrorCode.ACCOUNT_LOCKED,assertThrows(BizException.class,()->service.login(login(),"ip","ua")).getErrorCode());verify(values).set(startsWith("login:locked-once:"),eq("1"),any(Duration.class));}
 @Test void previousLockRequiresCaptcha(){when(users.selectOne(any(Wrapper.class))).thenReturn(account());when(redis.hasKey(startsWith("login:locked-once:"))).thenReturn(true);doThrow(new BizException(ErrorCode.PARAM_INVALID)).when(captcha).verifyAndConsume(any(),any());assertThrows(BizException.class,()->service.login(login(),null,null));verify(audit).recordLogin(argThat(l->"验证码".equals(l.getFailReason())));}
 @Test void loginSuccessMasksPhoneAndUsesStationRoleFloor(){SysUser u=account();u.setStationId(2L);when(users.selectOne(any(Wrapper.class))).thenReturn(u);when(encoder.matches(any(),any())).thenReturn(true);when(jwt.issue(any())).thenReturn("签发令牌");LoginVo vo=service.login(login(),"127.0.0.1","ua");assertEquals("签发令牌",vo.getToken());assertEquals("STATION_ADMIN",vo.getUser().getRole());assertFalse(vo.getUser().getPhone().contains("13800000000"));verify(audit).recordLogin(argThat(l->l.getResult()==1));}
 @Test void highestRoleSurvivesStationBindingAndUnknownRoles(){SysUser u=account();u.setStationId(2L);when(users.selectOne(any(Wrapper.class))).thenReturn(u);when(encoder.matches(any(),any())).thenReturn(true);SysUserRole l=new SysUserRole();l.setRoleId(9L);when(links.selectList(any(Wrapper.class))).thenReturn(List.of(l));SysRole normal=new SysRole();normal.setRoleCode("USER");SysRole unknown=new SysRole();unknown.setRoleCode("未知");SysRole platform=new SysRole();platform.setRoleCode("PLATFORM_ADMIN");when(roles.selectByIds(anyCollection())).thenReturn(List.of(normal,unknown,platform));LoginVo vo=service.login(login(),null,null);assertEquals("PLATFORM_ADMIN",vo.getUser().getRole());}
 @Test void refreshInvalidatesOldJtiAndUsesLatestAccount(){LoginUser u=new LoginUser();u.setUserId(1L);u.setJti("旧jti");AuthContext.set(u);when(jwt.remaining("旧令牌")).thenReturn(Duration.ofMinutes(5));when(users.selectById(1L)).thenReturn(account());when(jwt.issue(any())).thenReturn("新令牌");LoginVo vo=service.refresh("旧令牌");assertEquals("新令牌",vo.getToken());verify(values).set(RedisKeys.authDeny("旧jti"),"1",Duration.ofMinutes(5));verify(jwt).issue(argThat(n->n.getJti()==null&&n.getTokenVersion()==2L));service.logout("旧令牌");verify(values,times(2)).set(RedisKeys.authDeny("旧jti"),"1",Duration.ofMinutes(5));}
 @Test void refreshDeletedAccountCannotResurrect(){LoginUser u=new LoginUser();u.setUserId(1L);AuthContext.set(u);assertEquals(ErrorCode.UNAUTHENTICATED,assertThrows(BizException.class,()->service.refresh("token")).getErrorCode());verify(jwt,never()).issue(any());}
 @Test void loginRedisOutageRetainsPasswordCheck(){when(values.get(anyString())).thenThrow(new IllegalStateException("Redis故障"));when(users.selectOne(any(Wrapper.class))).thenReturn(account());when(encoder.matches(any(),any())).thenReturn(true);assertDoesNotThrow(()->service.login(login(),null,null));}
}
