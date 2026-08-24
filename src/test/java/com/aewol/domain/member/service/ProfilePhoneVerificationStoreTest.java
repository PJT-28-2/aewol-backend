package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.Sha256Util;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfilePhoneVerificationStoreTest {

    private static final String MEMBER_ID = "1";
    private static final String OTP_KEY =
            "profile:phone:verify:" + Sha256Util.lowercaseHex(MEMBER_ID);

    @Mock RedisTemplate<String, String> redisTemplate;
    private ProfilePhoneVerificationStore store;

    @BeforeEach
    void setUp() {
        store = new ProfilePhoneVerificationStore(redisTemplate);
    }

    @Test
    void issuesSixDigitCodeWithHashedMemberKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("300")))
                .thenReturn(300L);

        ProfilePhoneVerificationStore.IssuedVerification issued =
                store.issue(MEMBER_ID, "01012345678");

        assertTrue(issued.getCode().matches("\\d{6}"));
        assertEquals(300L, issued.getExpiresInSeconds());
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(),
                eq(issued.getStoredValue()), eq("300"));
        assertEquals(OTP_KEY, keys.getValue().get(0));
        assertFalse(keys.getValue().get(0).endsWith(MEMBER_ID));
    }

    @Test
    void verifyMarksPhoneVerifiedInsideRedis() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("123456"), eq("5")))
                .thenReturn("OK|01012345678");

        String phone = store.verify(MEMBER_ID, "123456");

        assertEquals("01012345678", phone);
        ArgumentCaptor<RedisScript<String>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), anyList(), eq("123456"), eq("5"));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("'VERIFIED|' .. phone"));
        assertTrue(lua.contains("attempts >= tonumber(ARGV[2])"));
    }

    @Test
    void consumeVerifiedRequiresExactVerifiedValue() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("VERIFIED|01099998888")))
                .thenReturn(1L, 0L);

        assertTrue(store.consumeVerified(MEMBER_ID, "01099998888"));
        assertFalse(store.consumeVerified(MEMBER_ID, "01099998888"));
    }

    @Test
    void missingOtpUsesSameBadRequestContract() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("5")))
                .thenReturn("MISSING");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> store.verify(MEMBER_ID, "123456"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("인증번호가 만료되었거나 유효하지 않습니다.", exception.getMessage());
    }
}
