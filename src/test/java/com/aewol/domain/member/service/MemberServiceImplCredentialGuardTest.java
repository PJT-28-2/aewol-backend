package com.aewol.domain.member.service;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.dto.MemberPasswordVerifyRequest;
import com.aewol.domain.member.dto.MemberPhoneSendCodeRequest;
import com.aewol.domain.member.dto.MemberPhoneVerifyCodeRequest;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.external.sms.SmsSendException;
import com.aewol.external.sms.SmsSender;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplCredentialGuardTest {

    @Mock MemberMapper memberMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthCredentialStore authCredentialStore;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock SmsSender smsSender;
    @Mock ProfilePhoneVerificationStore phoneVerificationStore;

    private MemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MemberServiceImpl(memberMapper, passwordEncoder, authCredentialStore,
                MemberAuthStateCache.withoutCache(memberMapper),
                redisRateLimiter, redisTemplate, smsSender, phoneVerificationStore);
    }

    @Test
    void fifthPasswordFailureLocksVerificationForFifteenMinutes() {
        when(memberMapper.findById("1")).thenReturn(localMember());
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry("member:password:failures:1", 900L)).thenReturn(5L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verifyPassword("1", verifyRequest("wrong")));

        assertEquals(429, exception.getStatus().value());
        assertEquals("비밀번호 확인 시도가 너무 많습니다. 15분 후 다시 시도해주세요.",
                exception.getMessage());
        verify(valueOperations).set(eq("member:password:lock:1"), eq("1"),
                eq(Duration.ofSeconds(900)));
    }

    @Test
    void lockedPasswordVerificationIsRejectedBeforeEncoder() {
        when(memberMapper.findById("1")).thenReturn(localMember());
        when(redisTemplate.hasKey("member:password:lock:1")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verifyPassword("1", verifyRequest("current-password")));

        assertEquals(429, exception.getStatus().value());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void phoneChangeWithoutCompletedSmsIsRejected() {
        when(memberMapper.findById("1")).thenReturn(localMember());
        when(phoneVerificationStore.consumeVerified("1", "01099998888")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateMember("1", updateRequest("01099998888")));

        assertEquals(400, exception.getStatus().value());
        assertEquals("전화번호 인증이 완료되지 않았거나 만료되었습니다.", exception.getMessage());
        verify(memberMapper, never()).updateProfile(any());
    }

    @Test
    void verifiedPhoneChangeUpdatesProfile() {
        when(memberMapper.findById("1")).thenReturn(localMember());
        when(phoneVerificationStore.consumeVerified("1", "01099998888")).thenReturn(true);
        when(memberMapper.existsActiveByPhoneExcludingMember("01099998888", "1")).thenReturn(false);

        service.updateMember("1", updateRequest("01099998888"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(memberMapper).updateProfile(captor.capture());
        assertEquals("01099998888", captor.getValue().get("phone"));
    }

    @Test
    void sendPhoneCodeRejectsCurrentNumberBeforeSms() {
        when(memberMapper.findById("1")).thenReturn(localMember());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode("1", phoneRequest("01012345678")));

        assertEquals("현재 전화번호와 같습니다.", exception.getMessage());
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void sendPhoneCodeIssuesOtpAndSendsSms() {
        when(memberMapper.findById("1")).thenReturn(localMember());
        when(redisRateLimiter.incrementWithExpiry(anyString(), eq(1800L))).thenReturn(1L);
        when(memberMapper.existsActiveByPhoneExcludingMember("01099998888", "1")).thenReturn(false);
        when(phoneVerificationStore.issue("1", "01099998888"))
                .thenReturn(new ProfilePhoneVerificationStore.IssuedVerification(
                        "1", "123456", "stored", 300L));

        var response = service.sendPhoneVerificationCode("1", phoneRequest("01099998888"));

        assertEquals(300L, response.getExpiresInSeconds());
        verify(smsSender).send("01099998888",
                "[AeWol] 전화번호 변경 인증번호: 123456 (5분 이내 입력)");
    }

    @Test
    void smsFailureDiscardsIssuedOtp() {
        when(memberMapper.findById("1")).thenReturn(localMember());
        when(redisRateLimiter.incrementWithExpiry(anyString(), eq(1800L))).thenReturn(1L);
        when(memberMapper.existsActiveByPhoneExcludingMember("01099998888", "1")).thenReturn(false);
        ProfilePhoneVerificationStore.IssuedVerification issued =
                new ProfilePhoneVerificationStore.IssuedVerification(
                        "1", "123456", "stored", 300L);
        when(phoneVerificationStore.issue("1", "01099998888")).thenReturn(issued);
        doThrow(new SmsSendException("rejected"))
                .when(smsSender).send(anyString(), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode("1", phoneRequest("01099998888")));

        assertEquals(503, exception.getStatus().value());
        verify(phoneVerificationStore).discard(issued);
    }

    @Test
    void verifyPhoneCodeRejectsMismatchedNumber() {
        when(memberMapper.findById("1")).thenReturn(localMember());
        when(phoneVerificationStore.verify("1", "123456")).thenReturn("01099998888");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verifyPhoneCode("1", verifyPhoneRequest("01011112222", "123456")));

        assertEquals(400, exception.getStatus().value());
        assertEquals("전화번호 인증이 완료되지 않았거나 만료되었습니다.", exception.getMessage());
    }

    private Map<String, Object> localMember() {
        Map<String, Object> member = new HashMap<>();
        member.put("member_id", 1L);
        member.put("phone", "01012345678");
        member.put("provider", "LOCAL");
        member.put("password", "encoded");
        member.put("profile_img", "profile.jpg");
        member.put("zip_code", "12345");
        member.put("address", "제주시 애월읍");
        member.put("address_detail", "101호");
        return member;
    }

    private MemberPasswordVerifyRequest verifyRequest(String currentPassword) {
        MemberPasswordVerifyRequest request = new MemberPasswordVerifyRequest();
        ReflectionTestUtils.setField(request, "currentPassword", currentPassword);
        return request;
    }

    private MemberUpdateRequest updateRequest(String phone) {
        MemberUpdateRequest request = new MemberUpdateRequest();
        ReflectionTestUtils.setField(request, "phone", phone);
        return request;
    }

    private MemberPhoneSendCodeRequest phoneRequest(String phone) {
        MemberPhoneSendCodeRequest request = new MemberPhoneSendCodeRequest();
        ReflectionTestUtils.setField(request, "phone", phone);
        return request;
    }

    private MemberPhoneVerifyCodeRequest verifyPhoneRequest(String phone, String code) {
        MemberPhoneVerifyCodeRequest request = new MemberPhoneVerifyCodeRequest();
        ReflectionTestUtils.setField(request, "phone", phone);
        ReflectionTestUtils.setField(request, "verificationCode", code);
        return request;
    }
}
