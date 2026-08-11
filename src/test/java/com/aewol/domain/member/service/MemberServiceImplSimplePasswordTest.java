package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.dto.SimplePasswordRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplSimplePasswordTest {

    @Mock MemberMapper memberMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthCredentialStore authCredentialStore;

    private MemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MemberServiceImpl(memberMapper, passwordEncoder, authCredentialStore);
    }

    @Test
    void should_hashAndSavePin_when_pinIsStrongAndMemberIsActive() {
        when(passwordEncoder.encode("482913")).thenReturn("encoded-pin");
        when(memberMapper.updateSimplePassword("member-1", "encoded-pin")).thenReturn(1);

        service.setSimplePassword("member-1", request("482913"));

        verify(memberMapper).updateSimplePassword("member-1", "encoded-pin");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123456", "654321", "012345", // 완전 연속 숫자
            "678901", "901234", "210987", // 9→0으로 넘어가는 순환 연속 숫자
            "451236", "509871",           // PIN 중간에 3자리 이상 연속이 섞인 경우
    })
    void should_throwSequentialMessage_when_pinContainsThreeOrMoreConsecutiveDigits(String sequentialPin) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request(sequentialPin)));

        assertEquals(400, exception.getStatus().value());
        assertEquals("연속된 숫자예요. 다른 비밀번호를 입력해주세요.", exception.getMessage());
        verify(memberMapper, never()).updateSimplePassword(any(), any());
        verify(passwordEncoder, never()).encode(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "111111", "000000", // 전부 같은 숫자
            "121212", "343434", // 두 자리 반복
            "123123", "987987", // 세 자리 반복
            "112233", "998877", // 두 자리씩 짝지어 오름차순/내림차순
    })
    void should_throwWeakMessage_when_pinIsOtherWeakPattern(String weakPin) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request(weakPin)));

        assertEquals(400, exception.getStatus().value());
        assertEquals("유추하기 쉬운 비밀번호예요. 다른 숫자를 입력해주세요.", exception.getMessage());
        verify(memberMapper, never()).updateSimplePassword(any(), any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void should_allowPin_when_pairsRepeatButAreNotSequential() {
        // 112233(순차 상승)과 달리 115533은 짝은 반복되지만 오름/내림차순이 아니라서 통과해야 함.
        when(passwordEncoder.encode("115533")).thenReturn("encoded-pin");
        when(memberMapper.updateSimplePassword("member-1", "encoded-pin")).thenReturn(1);

        service.setSimplePassword("member-1", request("115533"));

        verify(memberMapper).updateSimplePassword("member-1", "encoded-pin");
    }

    @Test
    void should_throwNotFound_when_memberIsMissingOrInactive() {
        when(passwordEncoder.encode("482913")).thenReturn("encoded-pin");
        when(memberMapper.updateSimplePassword(eq("member-1"), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setSimplePassword("member-1", request("482913")));

        assertEquals(404, exception.getStatus().value());
    }

    private SimplePasswordRequest request(String password) {
        SimplePasswordRequest request = new SimplePasswordRequest();
        ReflectionTestUtils.setField(request, "password", password);
        return request;
    }
}
