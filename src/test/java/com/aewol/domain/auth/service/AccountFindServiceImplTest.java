package com.aewol.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.dto.AccountFindResultResponse;
import com.aewol.domain.auth.dto.AccountFindSendCodeRequest;
import com.aewol.domain.auth.dto.AccountFindSendCodeResponse;
import com.aewol.domain.auth.dto.AccountFindVerifyRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.external.sms.SmsSendException;
import com.aewol.external.sms.SmsSender;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AccountFindServiceImplTest {

    @Mock MemberMapper memberMapper;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock SmsSender smsSender;

    private AccountFindServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountFindServiceImpl(memberMapper, redisTemplate, redisRateLimiter, smsSender);
        lenient().when(redisRateLimiter.incrementWithExpiry(anyString(), eq(1800L))).thenReturn(1L);
    }

    @Test
    void localAndKakaoMembersReceiveSmsAndOpaqueRequestIds() {
        when(memberMapper.findActiveForAccountFind("홍길동", "01012345678"))
                .thenReturn(List.of(member(1L, "LOCAL", "hong@example.com")))
                .thenReturn(List.of(member(2L, "KAKAO", "kakao@example.com")));
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1L);

        AccountFindSendCodeResponse local = service.sendVerificationCode(sendRequest(" 홍길동 ", "010-1234-5678"));
        AccountFindSendCodeResponse kakao = service.sendVerificationCode(sendRequest("홍길동", "01012345678"));

        assertTrue(local.getRequestId().matches("[0-9a-f-]{36}"));
        assertNotEquals(local.getRequestId(), kakao.getRequestId());
        assertEquals(300L, local.getExpiresInSeconds());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsSender, times(2)).send(eq("01012345678"), text.capture());
        text.getAllValues().forEach(value -> assertTrue(value.matches(
                "\\[AeWol] 계정 찾기 인증번호: \\d{6} \\(5분 이내 입력\\)")));
    }

    @Test
    void invalidPhoneIsRejectedBeforeRateLimitOrLookup() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendVerificationCode(sendRequest("홍길동", "01112345678")));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(redisRateLimiter, never()).incrementWithExpiry(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(memberMapper, never()).findActiveForAccountFind(anyString(), anyString());
    }

    @Test
    void missingAndDuplicateMembersUseSameResponseShapeWithoutSending() {
        when(memberMapper.findActiveForAccountFind(anyString(), anyString()))
                .thenReturn(List.of())
                .thenReturn(List.of(member(1L, "LOCAL", "a@example.com"),
                        member(2L, "LOCAL", "b@example.com")));

        AccountFindSendCodeResponse missing = service.sendVerificationCode(sendRequest("홍길동", "01012345678"));
        AccountFindSendCodeResponse duplicate = service.sendVerificationCode(sendRequest("홍길동", "01012345678"));

        assertEquals(300L, missing.getExpiresInSeconds());
        assertEquals(300L, duplicate.getExpiresInSeconds());
        assertTrue(missing.getRequestId().matches("[0-9a-f-]{36}"));
        assertTrue(duplicate.getRequestId().matches("[0-9a-f-]{36}"));
        verify(smsSender, never()).send(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sixthRequestIsRateLimitedBeforeMemberLookup() {
        when(redisRateLimiter.incrementWithExpiry(anyString(), eq(1800L))).thenReturn(6L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendVerificationCode(sendRequest("홍길동", "01012345678")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        verify(memberMapper, never()).findActiveForAccountFind(anyString(), anyString());
    }

    @Test
    void resendUsesAtomicReplacementScriptThatDeletesPreviousVerification() {
        when(memberMapper.findActiveForAccountFind(anyString(), anyString()))
                .thenReturn(List.of(member(1L, "LOCAL", "a@example.com")));
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1L);

        service.sendVerificationCode(sendRequest("홍길동", "01012345678"));
        service.sendVerificationCode(sendRequest("홍길동", "01012345678"));

        verify(redisTemplate, times(2)).execute(
                any(RedisScript.class), anyList(), anyString(), eq("300"),
                eq("account:find:verify:"), anyString());
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, times(2)).execute(script.capture(), anyList(),
                anyString(), anyString(), anyString(), anyString());
        assertTrue(script.getAllValues().get(0).getScriptAsString().contains("oldRequestId"));
        assertTrue(script.getAllValues().get(0).getScriptAsString().contains("DEL"));
    }

    @Test
    void smsFailureReturns503AndCleanupOnlyMatchesItsRequestId() {
        when(memberMapper.findActiveForAccountFind(anyString(), anyString()))
                .thenReturn(List.of(member(1L, "LOCAL", "a@example.com")));
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
        org.mockito.Mockito.doThrow(new SmsSendException("provider failure"))
                .when(smsSender).send(anyString(), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendVerificationCode(sendRequest("홍길동", "01012345678")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        verify(redisTemplate).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(script ->
                        script.getScriptAsString().contains("GET")
                                && script.getScriptAsString().contains("~= ARGV[1]")),
                anyList(), anyString());
    }

    @Test
    void verificationReturnsMaskedLocalEmailAndConsumesAtomically() {
        String requestId = UUIDHolder.VALUE;
        String subject = "a".repeat(64);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("account:find:verify:" + requestId))
                .thenReturn(subject + "|1|123456|0");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("OK|1");
        when(memberMapper.findActiveAccountFindResultById("1"))
                .thenReturn(member(1L, "LOCAL", "honggildong@naver.com"));

        AccountFindResultResponse response = service.verifyCode(verifyRequest(requestId, "123456"));

        assertEquals("LOCAL", response.getProvider());
        assertEquals("hong****@naver.com", response.getMaskedEmail());
        verify(redisTemplate).execute(
                org.mockito.ArgumentMatchers.<RedisScript<String>>argThat(script ->
                        script.getScriptAsString().contains("redis.call('DEL', KEYS[1])")
                                && script.getScriptAsString().contains("KEEPTTL")),
                anyList(), eq(requestId), eq("123456"), eq("5"));
    }

    @Test
    void kakaoVerificationNeverReturnsEmail() {
        stubSuccessfulVerification("2");
        when(memberMapper.findActiveAccountFindResultById("2"))
                .thenReturn(member(2L, "KAKAO", "must-not-leak@example.com"));

        AccountFindResultResponse response = service.verifyCode(verifyRequest(UUIDHolder.VALUE, "123456"));

        assertEquals("KAKAO", response.getProvider());
        assertNull(response.getMaskedEmail());
    }

    @Test
    void missingMalformedMismatchAndFifthFailureUseGenericError() {
        assertEquals("인증번호가 만료되었거나 유효하지 않습니다.",
                assertThrows(BusinessException.class,
                        () -> service.verifyCode(verifyRequest("tampered", "123456"))).getMessage());

        String subject = "b".repeat(64);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(subject + "|1|654321|4");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), eq("5")))
                .thenReturn("MISMATCH").thenReturn("MISSING");

        assertThrows(BusinessException.class,
                () -> service.verifyCode(verifyRequest(UUIDHolder.VALUE, "123456")));
        assertThrows(BusinessException.class,
                () -> service.verifyCode(verifyRequest(UUIDHolder.VALUE, "123456")));
    }

    private void stubSuccessfulVerification(String memberId) {
        String subject = "c".repeat(64);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(subject + "|" + memberId + "|123456|0");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("OK|" + memberId);
    }

    private AccountFindSendCodeRequest sendRequest(String name, String phone) {
        AccountFindSendCodeRequest request = new AccountFindSendCodeRequest();
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "phone", phone);
        return request;
    }

    private AccountFindVerifyRequest verifyRequest(String requestId, String code) {
        AccountFindVerifyRequest request = new AccountFindVerifyRequest();
        ReflectionTestUtils.setField(request, "requestId", requestId);
        ReflectionTestUtils.setField(request, "verificationCode", code);
        return request;
    }

    private Map<String, Object> member(long id, String provider, String email) {
        Map<String, Object> member = new HashMap<>();
        member.put("member_id", id);
        member.put("provider", provider);
        member.put("email", email);
        return member;
    }

    private static final class UUIDHolder {
        private static final String VALUE = "123e4567-e89b-12d3-a456-426614174000";
    }
}
