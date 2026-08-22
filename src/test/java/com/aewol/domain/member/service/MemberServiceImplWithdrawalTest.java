package com.aewol.domain.member.service;

import com.aewol.common.filter.MemberAuthStateCache;
import com.aewol.common.exception.BusinessException;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.dto.MemberWithdrawRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplWithdrawalTest {

    @Mock MemberMapper memberMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthCredentialStore authCredentialStore;

    private MemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MemberServiceImpl(memberMapper, passwordEncoder, authCredentialStore,
                MemberAuthStateCache.withoutCache(memberMapper));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void localMemberIsDeactivatedAndRefreshTokenIsDeletedOnlyAfterCommit() {
        when(memberMapper.findById("member-1")).thenReturn(member("LOCAL", 1, "encoded"));
        when(passwordEncoder.matches("current-password", "encoded")).thenReturn(true);
        when(memberMapper.deactivateActiveMember("member-1")).thenReturn(1);

        service.withdraw("member-1", request("current-password"));

        verify(memberMapper).deactivateActiveMember("member-1");
        verify(authCredentialStore, never()).deleteRefresh("member-1");

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(authCredentialStore).deleteRefresh("member-1");
    }

    @Test
    void missingOrWrongLocalPasswordChangesNeitherDbNorRedis() {
        when(memberMapper.findById("member-1")).thenReturn(member("LOCAL", 1, "encoded"));

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", null));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        BusinessException wrong = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request("wrong")));

        assertEquals(400, missing.getStatus().value());
        assertEquals("현재 비밀번호가 일치하지 않습니다.", missing.getMessage());
        assertEquals(400, wrong.getStatus().value());
        assertEquals("현재 비밀번호가 일치하지 않습니다.", wrong.getMessage());
        verify(memberMapper, never()).deactivateActiveMember("member-1");
        verify(authCredentialStore, never()).deleteRefresh("member-1");
    }

    @Test
    void blankLocalPasswordsAreRejectedBeforeDbOrRedisChanges() {
        when(memberMapper.findById("member-1")).thenReturn(member("LOCAL", 1, "encoded"));

        BusinessException empty = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request("")));
        BusinessException whitespace = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request("   ")));

        assertEquals(400, empty.getStatus().value());
        assertEquals(400, whitespace.getStatus().value());
        verify(passwordEncoder, never()).matches(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(memberMapper, never()).deactivateActiveMember("member-1");
        verify(authCredentialStore, never()).deleteRefresh("member-1");
    }

    @Test
    void kakaoMemberIgnoresPasswordAndDeactivates() {
        when(memberMapper.findById("member-1")).thenReturn(member("KAKAO", 1, null));
        when(memberMapper.deactivateActiveMember("member-1")).thenReturn(1);

        service.withdraw("member-1", request("ignored"));

        verify(passwordEncoder, never()).matches(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(memberMapper).deactivateActiveMember("member-1");
    }

    @Test
    void unknownOrNullProviderIsRejectedAsInvalidServerData() {
        when(memberMapper.findById("member-1"))
                .thenReturn(member("OTHER", 1, null), member(null, 1, null));

        assertThrows(IllegalStateException.class, () -> service.withdraw("member-1", null));
        assertThrows(IllegalStateException.class, () -> service.withdraw("member-1", null));

        verify(passwordEncoder, never()).matches(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(memberMapper, never()).deactivateActiveMember("member-1");
        verify(authCredentialStore, never()).deleteRefresh("member-1");
    }

    @Test
    void inactiveOrConcurrentWithdrawalReturnsConflictWithoutRedisChange() {
        when(memberMapper.findById("member-1"))
                .thenReturn(member("KAKAO", 0, null), member("KAKAO", 1, null));
        when(memberMapper.deactivateActiveMember("member-1")).thenReturn(0);

        BusinessException inactive = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", null));
        BusinessException concurrent = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", null));

        assertEquals(409, inactive.getStatus().value());
        assertEquals(409, concurrent.getStatus().value());
        verify(authCredentialStore, never()).deleteRefresh("member-1");
    }

    @Test
    void dbFailureDoesNotRegisterRefreshTokenCleanup() {
        when(memberMapper.findById("member-1")).thenReturn(member("KAKAO", 1, null));
        when(memberMapper.deactivateActiveMember("member-1"))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThrows(RuntimeException.class, () -> service.withdraw("member-1", null));

        assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
        verify(authCredentialStore, never()).deleteRefresh("member-1");
    }

    @Test
    void redisFailureAfterCommitDoesNotEscape() {
        when(memberMapper.findById("member-1")).thenReturn(member("KAKAO", 1, null));
        when(memberMapper.deactivateActiveMember("member-1")).thenReturn(1);
        doThrow(new RuntimeException("redis unavailable"))
                .when(authCredentialStore).deleteRefresh("member-1");

        service.withdraw("member-1", null);

        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
    }

    private MemberWithdrawRequest request(String currentPassword) {
        MemberWithdrawRequest request = new MemberWithdrawRequest();
        ReflectionTestUtils.setField(request, "currentPassword", currentPassword);
        return request;
    }

    private Map<String, Object> member(String provider, int active, String password) {
        Map<String, Object> member = new HashMap<>();
        member.put("provider", provider);
        member.put("is_active", active);
        member.put("password", password);
        return member;
    }
}
