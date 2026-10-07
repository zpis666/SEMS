package com.sems.unit.auth;
import com.sems.common.BizException;
import com.sems.config.SemsProperties;
import com.sems.security.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 固定HS256、签名/过期、声明约束和双密钥轮换的单元回归。 */
class JwtContractTest {
 static final String KEY="0123456789abcdef0123456789abcdef";
 SemsProperties props(String key){SemsProperties p=new SemsProperties();p.getJwt().setSecret(key);p.getJwt().setAccessTtl(Duration.ofHours(2));return p;}
 LoginUser principal(){LoginUser u=new LoginUser();u.setUserId(7L);u.setRole("USER");u.setPhone("13800000000");u.setNickname("测试");u.setTokenVersion(3L);return u;}
 @Test void issueRoundTripsAndFillsJtiAndExpiry(){JwtService s=new JwtService(props(KEY));LoginUser u=principal();String token=s.issue(u);assertNotNull(u.getJti());assertNotNull(u.getExpiresAt());LoginUser parsed=s.parse(token);assertEquals(7L,parsed.getUserId());assertEquals(3L,parsed.getTokenVersion());assertEquals("USER",parsed.getRole());assertEquals(u.getJti(),parsed.getJti());assertTrue(s.remaining(token).compareTo(Duration.ZERO)>0);assertEquals(32,s.newJti().length());assertThrows(IllegalArgumentException.class,()->s.issue(null));assertThrows(IllegalArgumentException.class,()->s.issue(new LoginUser()));}
 @Test void missingWeakKeyAndInvalidTtlFailAtConfigurationBoundary(){assertThrows(IllegalStateException.class,()->new JwtService(null));assertThrows(IllegalStateException.class,()->new JwtService(props("短密钥")));SemsProperties p=props(KEY);p.getJwt().setAccessTtl(Duration.ZERO);assertThrows(IllegalStateException.class,()->new JwtService(p).issue(principal()));p.getJwt().setPreviousSecret("fedcba9876543210fedcba9876543210");p.getJwt().setRotationGrace(Duration.ZERO);assertThrows(IllegalStateException.class,()->new JwtService(p));}
 @Test void badFormatAlgAndSignatureAreRejectedWithoutDetails(){JwtService s=new JwtService(props(KEY));for(String token:List.of("","a","a.b.c.d","$.e.f",Base64.getUrlEncoder().withoutPadding().encodeToString("bad-json".getBytes())+".e.f",Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())+".e.f",Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes())+".e.f")){assertThrows(BizException.class,()->s.parse(token));assertEquals(Duration.ZERO,s.remaining(token));}assertThrows(BizException.class,()->s.parse(null));String other=new JwtService(props("fedcba9876543210fedcba9876543210")).issue(principal());assertThrows(BizException.class,()->s.parse(other));}
 @Test void expiredMalformedAndMissingClaimsCannotAuthenticate(){JwtService s=new JwtService(props(KEY));var key=Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8));String expired=Jwts.builder().subject("7").id("jti").claim("tokenVersion",3).expiration(Date.from(Instant.now().minusSeconds(60))).signWith(key,Jwts.SIG.HS256).compact();assertThrows(BizException.class,()->s.parse(expired));assertEquals(Duration.ZERO,s.remaining(expired));for(String subject:List.of("不是数字","7")){String token=Jwts.builder().subject(subject).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key,Jwts.SIG.HS256).compact();assertThrows(BizException.class,()->s.parse(token));}String stringVersion=Jwts.builder().subject("7").id("jti").claim("tokenVersion","3").claim("stationId","2").expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key,Jwts.SIG.HS256).compact();assertEquals(2L,s.parse(stringVersion).getStationId());}
 @Test void previousKeyIsOnlyAcceptedDuringRotationGrace(){String oldKey="fedcba9876543210fedcba9876543210";String old=new JwtService(props(oldKey)).issue(principal());SemsProperties p=props(KEY);p.getJwt().setPreviousSecret(oldKey);JwtService rotated=new JwtService(p);assertEquals(7L,rotated.parse(old).getUserId());ReflectionTestUtils.setField(rotated,"previousKeyAcceptUntil",Instant.now().minusSeconds(1));assertThrows(BizException.class,()->rotated.parse(old));assertEquals(7L,rotated.parse(rotated.issue(principal())).getUserId());}
}
