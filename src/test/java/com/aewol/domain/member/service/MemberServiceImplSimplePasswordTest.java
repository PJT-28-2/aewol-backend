package com.aewol.domain.member.service;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.dto.SimplePasswordRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.external.sms.SmsSender;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplSimplePasswordTest {

    @Mock MemberMapper memberMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthCredentialStore authCredentialStore;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock RedisTemplate<String, String> redisTemplate;
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
    void should_hashAndSavePin_when_pinIsStrongAndMemberHasNoExistingPin() {
        when(memberMapper.findById("member-1")).thenReturn(memberWithoutPin());
        when(passwordEncoder.encode("482913")).thenReturn("encoded-pin");
        when(memberMapper.updateSimplePassword("member-1", "encoded-pin")).thenReturn(1);

        service.setSimplePassword("member-1", request("482913", null));

        verify(memberMapper).updateSimplePassword("member-1", "encoded-pin");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123456", "654321", "012345", // 완전 연속 숫자
            "678901", "901234", "210987", // 9→0으로 넘어가는 순환 연속 숫자
            "451236", "509871",           // PIN 중간에 3자리 이상 연속이 섞인 경우
    })
    void should_throwSequentialMessage_when_pinContainsThreeOrMoreConsecutiveDigits(String sequentialPin) {
        when(memberMapper.findById("member-1")).thenReturn(memberWithoutPin());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request(sequentialPin, null)));

        assertEquals(400, exception.getStatus().value());
        assertEquals("연속된 숫자예요. 다른 비밀번호를 입력해주세요.", exception.getMessage());
        verify(memberMapper, never()).updateSimplePassword(any(), any());
        verify(passwordEncoder, never()).encode(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "111111", "000000", // 전부 같은 숫자
            "121212", "343434", // 두 자리 반복
            "246246", "531531", // 세 자리 반복 (123123/987987은 "123"/"987"이 연속 3자리라
                                 // hasSequentialRun에 먼저 걸려서 이 케이스로 부적절 — CI에서 발견)
            "112233", "998877", // 두 자리씩 짝지어 오름차순/내림차순
    })
    void should_throwWeakMessage_when_pinIsOtherWeakPattern(String weakPin) {
        when(memberMapper.findById("member-1")).thenReturn(memberWithoutPin());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request(weakPin, null)));

        assertEquals(400, exception.getStatus().value());
        assertEquals("유추하기 쉬운 비밀번호예요. 다른 숫자를 입력해주세요.", exception.getMessage());
        verify(memberMapper, never()).updateSimplePassword(any(), any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void should_allowPin_when_pairsRepeatButAreNotSequential() {
        // 112233(순차 상승)과 달리 115533은 짝은 반복되지만 오름/내림차순이 아니라서 통과해야 함.
        when(memberMapper.findById("member-1")).thenReturn(memberWithoutPin());
        when(passwordEncoder.encode("115533")).thenReturn("encoded-pin");
        when(memberMapper.updateSimplePassword("member-1", "encoded-pin")).thenReturn(1);

        service.setSimplePassword("member-1", request("115533", null));

        verify(memberMapper).updateSimplePassword("member-1", "encoded-pin");
    }

    @Test
    void should_throwNotFound_when_memberIsMissing() {
        when(memberMapper.findById("member-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request("482913", null)));

        assertEquals(404, exception.getStatus().value());
        verify(memberMapper, never()).updateSimplePassword(any(), any());
    }

    @Test
    void should_throwMismatchMessage_when_resettingWithoutCurrentPassword() {
        // 이미 PIN이 설정된 회원 — 재설정이므로 currentPassword가 없으면 막아야 한다
        // (Access Token만으로 기존 PIN을 덮어써서 송금 인증을 우회하는 것 방지).
        when(memberMapper.findById("member-1")).thenReturn(memberWithPin("encoded-old-pin"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request("482913", null)));

        assertEquals(400, exception.getStatus().value());
        assertEquals("기존 간편 비밀번호가 일치하지 않습니다.", exception.getMessage());
        verify(memberMapper, never()).updateSimplePassword(any(), any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void should_throwMismatchMessage_when_resettingWithWrongCurrentPassword() {
        when(memberMapper.findById("member-1")).thenReturn(memberWithPin("encoded-old-pin"));
        when(passwordEncoder.matches("000000", "encoded-old-pin")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request("482913", "000000")));

        assertEquals(400, exception.getStatus().value());
        assertEquals("기존 간편 비밀번호가 일치하지 않습니다.", exception.getMessage());
        verify(memberMapper, never()).updateSimplePassword(any(), any());
    }

    @Test
    void should_allowReset_when_currentPasswordMatches() {
        when(memberMapper.findById("member-1")).thenReturn(memberWithPin("encoded-old-pin"));
        when(passwordEncoder.matches("112358", "encoded-old-pin")).thenReturn(true);
        when(passwordEncoder.encode("482913")).thenReturn("encoded-new-pin");
        when(memberMapper.updateSimplePassword("member-1", "encoded-new-pin")).thenReturn(1);

        service.setSimplePassword("member-1", request("482913", "112358"));

        verify(memberMapper).updateSimplePassword("member-1", "encoded-new-pin");
    }

    @Test
    void should_throwConflict_when_updateAffectsNoActiveMember() {
        when(memberMapper.findById("member-1")).thenReturn(memberWithoutPin());
        when(passwordEncoder.encode("482913")).thenReturn("encoded-pin");
        when(memberMapper.updateSimplePassword("member-1", "encoded-pin")).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request("482913", null)));

        assertEquals(409, exception.getStatus().value());
        assertEquals("비밀번호를 변경할 수 없는 회원 상태입니다.", exception.getMessage());
    }

    private Map<String, Object> memberWithoutPin() {
        Map<String, Object> member = new HashMap<>();
        member.put("simple_password", null);
        return member;
    }

    private Map<String, Object> memberWithPin(String encodedPin) {
        Map<String, Object> member = new HashMap<>();
        member.put("simple_password", encodedPin);
        return member;
    }

    private SimplePasswordRequest request(String password, String currentPassword) {
        SimplePasswordRequest request = new SimplePasswordRequest();
        ReflectionTestUtils.setField(request, "password", password);
        ReflectionTestUtils.setField(request, "currentPassword", currentPassword);
        return request;
    }
}
