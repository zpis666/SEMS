package com.sems.unit.auth;
import com.sems.common.*;
import com.sems.infra.RedisKeys;
import com.sems.module.auth.service.CaptchaService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import java.time.Duration;
import java.util.Base64;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class CaptchaServiceTest {
 @Test void generatedPngIsStoredAndConsumedAtomically(){StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> v=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(v);CaptchaService s=new CaptchaService(redis);var image=s.generate();assertNotNull(image.getCaptchaId());assertTrue(image.getImageBase64().length()>100);verify(v).set(eq(RedisKeys.captcha(image.getCaptchaId())),matches("[A-Z0-9]{4}"),any(Duration.class));when(v.getAndDelete(RedisKeys.captcha("图1"))).thenReturn("ABCD",null);s.verifyAndConsume("图1"," abcd ");assertEquals(ErrorCode.PARAM_INVALID,assertThrows(BizException.class,()->s.verifyAndConsume("图1","ABCD")).getErrorCode());}
 @Test void missingWrongAndRedisFailureAreFailClosed(){StringRedisTemplate r=mock(StringRedisTemplate.class);ValueOperations<String,String> v=mock(ValueOperations.class);when(r.opsForValue()).thenReturn(v);CaptchaService s=new CaptchaService(r);assertThrows(BizException.class,()->s.verifyAndConsume(null,"ABCD"));assertThrows(BizException.class,()->s.verifyAndConsume("id",""));when(v.getAndDelete(anyString())).thenReturn("ABCD");assertThrows(BizException.class,()->s.verifyAndConsume("id","ZZZZ"));when(v.getAndDelete(anyString())).thenThrow(new IllegalStateException("故障"));assertEquals(ErrorCode.SYSTEM_ERROR,assertThrows(BizException.class,()->s.verifyAndConsume("id","ABCD")).getErrorCode());doThrow(new IllegalStateException("故障")).when(v).set(anyString(),anyString(),any(Duration.class));assertEquals(ErrorCode.SYSTEM_ERROR,assertThrows(BizException.class,s::generate).getErrorCode());}
}
