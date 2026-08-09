package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.smtp.EmailService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplSignupTest {

    private static final String EMAIL = "local@aewol.com";
    private static final String CODE = "123456";
    private static final String COMPLETED_KEY = "signup:verify:completed:" + EMAIL;
    private static final String COMPLETED_VALUE = "550e8400-e29b-41d4-a716-446655440000|" + CODE;

    @Mock MemberMapper memberMapper;
    @Mock WalletMapper walletMapper;
    @Mock NotificationSettingMapper notificationSettingMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock EmailService emailService;
    @Mock KakaoAuthClient kakaoAuthClient;
    @Mock AuthCredentialStore authCredentialStore;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(memberMapper, walletMapper, notificationSettingMapper,
                jwtUtil, passwordEncoder, redisTemplate, emailService, kakaoAuthClient, authCredentialStore);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createsVerifiedLocalMemberWalletAndExplicitNotificationSettings() {
        stubCompletedVerification();
        when(passwordEncoder.encode("password12")).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL)).thenReturn(null);
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 7L);
            return null;
        }).when(memberMapper).insert(any());

        SignupResponse response = authService.signup(request(true));

        assertEquals(7L, response.getUserId());
        assertEquals(EMAIL, response.getEmail());
        ArgumentCaptor<Map<String, Object>> memberCaptor = ArgumentCaptor.forClass(Map.class);
        verify(memberMapper).insert(memberCaptor.capture());
        assertEquals("encoded", memberCaptor.getValue().get("password"));
        assertEquals("LOCAL", memberCaptor.getValue().get("provider"));
        assertEquals("Y", memberCaptor.getValue().get("emailVerified"));
        verify(walletMapper).insert(argThat(wallet -> Long.valueOf(7L).equals(wallet.get("memberId"))
                && "MAIN".equals(wallet.get("walletType")) && Integer.valueOf(0).equals(wallet.get("balance"))));
        verify(notificationSettingMapper).insert(argThat(setting -> Long.valueOf(7L).equals(setting.get("memberId"))
                && Boolean.TRUE.equals(setting.get("paymentEnabled"))
                && Boolean.TRUE.equals(setting.get("recurringPaymentEnabled"))
                && Boolean.TRUE.equals(setting.get("familyShareEnabled"))
                && Boolean.TRUE.equals(setting.get("communityEnabled"))
                && Boolean.TRUE.equals(setting.get("marketingEnabled"))));
        verify(jwtUtil, never()).generateAccessToken(anyString(), anyString());
        verify(jwtUtil, never()).generateRefreshToken(anyString());
    }

    @Test
    void rejectsMissingExpiredMalformedAndMismatchedCompletionValues() {
        when(valueOperations.get(COMPLETED_KEY)).thenReturn(null, "true", COMPLETED_VALUE.replace(CODE, "654321"));

        BusinessException missing = assertThrows(BusinessException.class, () -> authService.signup(request(false)));
        BusinessException malformed = assertThrows(BusinessException.class, () -> authService.signup(request(false)));
        BusinessException mismatch = assertThrows(BusinessException.class, () -> authService.signup(request(false)));

        assertEquals(400, missing.getStatus().value());
        assertEquals(400, malformed.getStatus().value());
        assertEquals(400, mismatch.getStatus().value());
        verify(memberMapper, never()).insert(any());
    }

    @Test
    void activeEmailConflictStopsBeforeInsert() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> authService.signup(request(false)));

        assertEquals(409, exception.getStatus().value());
        verify(memberMapper, never()).insert(any());
        verify(walletMapper, never()).insert(any());
    }

    @Test
    void duplicateInsertIsConvertedToConflictAndDoesNotRegisterCleanup() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL)).thenReturn(null);
        doThrow(new DuplicateKeyException("duplicate")).when(memberMapper).insert(any());

        BusinessException exception = assertThrows(BusinessException.class, () -> authService.signup(request(false)));

        assertEquals(409, exception.getStatus().value());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verify(walletMapper, never()).insert(any());
    }

    @Test
    void recoveryWithExistingNotificationUsesUpsertAndPreservesWallet() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL)).thenReturn(inactive("LOCAL", 9L, true));
        when(memberMapper.restoreLocalMember(any())).thenReturn(1);

        SignupResponse response = authService.signup(request(false));

        assertEquals(9L, response.getUserId());
        verify(memberMapper).findLatestInactiveByEmailForUpdate(EMAIL);
        verify(memberMapper).restoreLocalMember(argThat(member -> "encoded".equals(member.get("password"))
                && "Y".equals(member.get("emailVerified")) && Long.valueOf(9L).equals(member.get("memberId"))));
        verify(walletMapper, never()).insert(any());
        verify(notificationSettingMapper).upsertForRecovery(9L, false);
        verify(notificationSettingMapper, never()).insert(any());
    }

    @Test
    void recoveryDoesNotDependOnRedisCredentialCleanup() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL))
                .thenReturn(inactive("LOCAL", 9L, true));
        when(memberMapper.restoreLocalMember(any())).thenReturn(1);

        SignupResponse response = authService.signup(request(false));

        assertEquals(9L, response.getUserId());
        verify(memberMapper).restoreLocalMember(any());
        verify(notificationSettingMapper).upsertForRecovery(9L, false);
    }

    @Test
    void legacyRecoveryWithoutNotificationUsesSameAtomicUpsert() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL)).thenReturn(inactive("LOCAL", 9L, true));
        when(memberMapper.restoreLocalMember(any())).thenReturn(1);

        authService.signup(request(true));

        verify(notificationSettingMapper).upsertForRecovery(9L, true);
        verify(notificationSettingMapper, never()).insert(any());
    }

    @Test
    void blocksWithdrawnKakaoAndLocalMemberOlderThanThirtyDays() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL))
                .thenReturn(inactive("KAKAO", 9L, true), inactive("LOCAL", 10L, false));

        BusinessException kakao = assertThrows(BusinessException.class, () -> authService.signup(request(false)));
        BusinessException expired = assertThrows(BusinessException.class, () -> authService.signup(request(false)));

        assertEquals(409, kakao.getStatus().value());
        assertEquals(409, expired.getStatus().value());
        verify(memberMapper, never()).restoreLocalMember(any());
    }

    @Test
    void afterCommitUsesOriginalValueAndCleanupFailureDoesNotEscape() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL)).thenReturn(null);
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 7L);
            return null;
        }).when(memberMapper).insert(any());
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RuntimeException("redis unavailable"));

        authService.signup(request(false));
        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(redisTemplate).execute(
                argThat(script -> script.getScriptAsString().contains("stored == ARGV[1]")),
                eq(List.of(COMPLETED_KEY)), eq(COMPLETED_VALUE));
    }

    @Test
    void dbFailureLeavesCompletionKeyAndDoesNotRegisterCallback() {
        stubCompletedVerification();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(memberMapper.findLatestInactiveByEmailForUpdate(EMAIL)).thenReturn(null);
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 7L);
            return null;
        }).when(memberMapper).insert(any());
        doThrow(new RuntimeException("wallet failure")).when(walletMapper).insert(any());

        assertThrows(RuntimeException.class, () -> authService.signup(request(false)));

        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    private void stubCompletedVerification() {
        when(valueOperations.get(COMPLETED_KEY)).thenReturn(COMPLETED_VALUE);
    }

    private SignupRequest request(boolean marketing) {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", EMAIL);
        ReflectionTestUtils.setField(request, "verificationCode", CODE);
        ReflectionTestUtils.setField(request, "password", "password12");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "phone", "01012345678");
        ReflectionTestUtils.setField(request, "zipCode", "12345");
        ReflectionTestUtils.setField(request, "address", "제주시 애월읍");
        ReflectionTestUtils.setField(request, "addressDetail", "101호");
        ReflectionTestUtils.setField(request, "terms", true);
        ReflectionTestUtils.setField(request, "privacy", true);
        ReflectionTestUtils.setField(request, "marketing", marketing);
        return request;
    }

    private Map<String, Object> inactive(String provider, long memberId, boolean recoverable) {
        Map<String, Object> member = new HashMap<>();
        member.put("member_id", memberId);
        member.put("provider", provider);
        member.put("recoverable_within_30_days", recoverable ? 1 : 0);
        return member;
    }

}
