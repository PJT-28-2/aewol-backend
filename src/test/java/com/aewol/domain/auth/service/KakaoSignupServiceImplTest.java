package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.dto.KakaoAuthStatus;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeRequest;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeResponse;
import com.aewol.domain.auth.dto.KakaoPhoneVerifyCodeRequest;
import com.aewol.domain.auth.dto.KakaoRegistrationSession;
import com.aewol.domain.auth.dto.KakaoSignupCompleteRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.sms.SmsSendException;
import com.aewol.external.sms.SmsSender;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class KakaoSignupServiceImplTest {

    private static final String REGISTRATION_TOKEN = "a".repeat(43);
    private static final String TOKEN_HASH =
            "66d34fba71f8f450f7e45598853e53bfc23bbd129027cbb131a2f4ffd7878cd0";
    private static final String PHONE_HASH =
            "e60124f2fe2045215abda1ae912aa80bb66dab5fc231a758387682c9c0e70c01";

    @Mock MemberMapper memberMapper;
    @Mock WalletMapper walletMapper;
    @Mock NotificationSettingMapper notificationSettingMapper;
    @Mock JwtUtil jwtUtil;
    @Mock AuthCredentialStore authCredentialStore;
    @Mock KakaoRegistrationStore registrationStore;
    @Mock KakaoPhoneVerificationStore phoneVerificationStore;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock SmsSender smsSender;

    private KakaoSignupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KakaoSignupServiceImpl(
                memberMapper,
                walletMapper,
                notificationSettingMapper,
                jwtUtil,
                authCredentialStore,
                registrationStore,
                phoneVerificationStore,
                redisRateLimiter,
                smsSender);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendCodeUsesHashedRateLimitsAndCreatesNoSignupDataOrTokens() {
        KakaoPhoneSendCodeRequest request = sendRequest("01012345678");
        when(registrationStore.getAvailable(REGISTRATION_TOKEN)).thenReturn(session(null));
        when(registrationStore.redisKey(REGISTRATION_TOKEN))
                .thenReturn("kakao:registration:hashed-token");
        when(redisRateLimiter.incrementWithExpiry(anyString(),
                org.mockito.ArgumentMatchers.eq(1800L))).thenReturn(1L);
        when(phoneVerificationStore.issue(anyString(), anyString(), anyString()))
                .thenReturn(new KakaoPhoneVerificationStore.IssuedVerification(
                        TOKEN_HASH, "123456",
                        "01012345678|123456|0|00000000000000000000000000000000", 300L));

        KakaoPhoneSendCodeResponse response = service.sendPhoneVerificationCode(request);

        assertEquals(300L, response.getExpiresInSeconds());
        ArgumentCaptor<String> rateKeys = ArgumentCaptor.forClass(String.class);
        verify(redisRateLimiter, times(2)).incrementWithExpiry(rateKeys.capture(),
                org.mockito.ArgumentMatchers.eq(1800L));
        assertTrue(rateKeys.getAllValues().stream().noneMatch(key ->
                key.contains(REGISTRATION_TOKEN) || key.contains("01012345678")));
        verify(smsSender).send("01012345678",
                "[AeWol] 카카오 회원가입 인증번호: 123456 (5분 이내 입력)");
        verify(memberMapper, never()).insert(any());
        verify(walletMapper, never()).insert(any());
        verify(notificationSettingMapper, never()).insert(any());
        verify(jwtUtil, never()).generateAccessToken(anyString(), anyString());
        verify(jwtUtil, never()).generateRefreshToken(anyString());
        verify(authCredentialStore, never()).storeRefresh(anyString(), anyString());
    }

    @Test
    void nonexistentSessionAndDuplicatePhoneDoNotSendSms() {
        KakaoPhoneSendCodeRequest request = sendRequest("01012345678");
        when(registrationStore.getAvailable(REGISTRATION_TOKEN))
                .thenThrow(new BusinessException("유효하지 않은 세션"))
                .thenReturn(session(null));
        assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode(request));

        when(memberMapper.existsActiveByPhone("01012345678")).thenReturn(true);
        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode(request));

        assertEquals(HttpStatus.CONFLICT, duplicate.getStatus());
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void duplicatePhoneConsumesBothRateLimitsBeforeConflict() {
        KakaoPhoneSendCodeRequest request = sendRequest("01012345678");
        when(registrationStore.getAvailable(REGISTRATION_TOKEN)).thenReturn(session(null));
        when(redisRateLimiter.incrementWithExpiry(anyString(),
                org.mockito.ArgumentMatchers.eq(1800L))).thenReturn(1L);
        when(memberMapper.existsActiveByPhone("01012345678")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode(request));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        InOrder order = inOrder(registrationStore, redisRateLimiter, memberMapper);
        order.verify(registrationStore).getAvailable(REGISTRATION_TOKEN);
        order.verify(redisRateLimiter).incrementWithExpiry(
                KakaoSignupServiceImpl.TOKEN_RATE_LIMIT_PREFIX + TOKEN_HASH, 1800L);
        order.verify(redisRateLimiter).incrementWithExpiry(
                KakaoSignupServiceImpl.PHONE_RATE_LIMIT_PREFIX + PHONE_HASH,
                1800L);
        order.verify(memberMapper).existsActiveByPhone("01012345678");
        verify(phoneVerificationStore, never()).issue(anyString(), anyString(), anyString());
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void phoneAndRegistrationTokenRateLimitsReturn429() {
        KakaoPhoneSendCodeRequest request = sendRequest("01012345678");
        when(registrationStore.getAvailable(REGISTRATION_TOKEN)).thenReturn(session(null));
        when(redisRateLimiter.incrementWithExpiry(anyString(),
                org.mockito.ArgumentMatchers.eq(1800L)))
                .thenReturn(6L)
                .thenReturn(1L, 6L);

        BusinessException tokenLimited = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode(request));
        BusinessException phoneLimited = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode(request));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, tokenLimited.getStatus());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, phoneLimited.getStatus());
        verify(phoneVerificationStore, never()).issue(anyString(), anyString(), anyString());
    }

    @Test
    void smsFailureDiscardsOnlyIssuedOtpAndReturns503() {
        KakaoPhoneSendCodeRequest request = sendRequest("01012345678");
        KakaoPhoneVerificationStore.IssuedVerification issued =
                new KakaoPhoneVerificationStore.IssuedVerification(
                        TOKEN_HASH, "123456",
                        "01012345678|123456|0|00000000000000000000000000000000", 300L);
        when(registrationStore.getAvailable(REGISTRATION_TOKEN)).thenReturn(session(null));
        when(registrationStore.redisKey(REGISTRATION_TOKEN)).thenReturn("registration-key");
        when(redisRateLimiter.incrementWithExpiry(anyString(),
                org.mockito.ArgumentMatchers.eq(1800L))).thenReturn(1L);
        when(phoneVerificationStore.issue(anyString(), anyString(), anyString()))
                .thenReturn(issued);
        doThrow(new SmsSendException("raw provider response"))
                .when(smsSender).send(anyString(), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode(request));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertFalse(exception.getMessage().contains("provider"));
        verify(phoneVerificationStore).discard(issued);
    }

    @Test
    void rateLimitRedisFailureReturns503BeforeOtpOrSms() {
        KakaoPhoneSendCodeRequest request = sendRequest("01012345678");
        when(registrationStore.getAvailable(REGISTRATION_TOKEN)).thenReturn(session(null));
        when(redisRateLimiter.incrementWithExpiry(anyString(),
                org.mockito.ArgumentMatchers.eq(1800L)))
                .thenThrow(new RuntimeException("raw redis key"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendPhoneVerificationCode(request));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertFalse(exception.getMessage().contains("redis"));
        verify(phoneVerificationStore, never()).issue(anyString(), anyString(), anyString());
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void verifyUsesPhoneStoredWithOtpAndUpdatesRegistrationSession() {
        KakaoPhoneVerifyCodeRequest request = verifyRequest("123456");
        when(registrationStore.getAvailable(REGISTRATION_TOKEN)).thenReturn(session(null));
        when(phoneVerificationStore.verify(anyString(), org.mockito.ArgumentMatchers.eq("123456")))
                .thenReturn("01012345678");

        service.verifyPhoneCode(request);

        verify(registrationStore).updateVerifiedPhone(REGISTRATION_TOKEN, "01012345678");
        verify(phoneVerificationStore).consumeVerified(anyString(),
                org.mockito.ArgumentMatchers.eq("01012345678"));
        verify(memberMapper, never()).insert(any());
        verify(walletMapper, never()).insert(any());
        verify(notificationSettingMapper, never()).insert(any());
        verify(jwtUtil, never()).generateAccessToken(anyString(), anyString());
        verify(authCredentialStore, never()).storeRefresh(anyString(), anyString());
    }

    @Test
    void completeCreatesKakaoMemberWalletNotificationsAndTokensFromSession() {
        KakaoRegistrationStore.Claim claim = claim(session("01012345678"));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(claim);
        stubNoDuplicateMember();
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 7L);
            return null;
        }).when(memberMapper).insert(any());
        when(jwtUtil.generateAccessToken("7", "USER")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("7")).thenReturn("refresh-token");

        KakaoOAuthResponse response = service.complete(completeRequest(true));

        assertEquals(KakaoAuthStatus.LOGIN_COMPLETE, response.getAuthStatus());
        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertNull(response.getRegistrationToken());
        verify(memberMapper).insert(argThat(member ->
                "kakao-id".equals(member.get("providerId"))
                        && "member@example.com".equals(member.get("email"))
                        && "홍길동".equals(member.get("name"))
                        && "01012345678".equals(member.get("phone"))
                        && "KAKAO".equals(member.get("provider"))
                        && member.get("password") == null
                        && "Y".equals(member.get("emailVerified"))
                        && "12345".equals(member.get("zipCode"))
                        && "제주시 애월읍".equals(member.get("address"))
                        && "101호".equals(member.get("addressDetail"))));
        verify(walletMapper).insert(argThat(wallet ->
                Long.valueOf(7L).equals(wallet.get("memberId"))
                        && "MAIN".equals(wallet.get("walletType"))
                        && Integer.valueOf(0).equals(wallet.get("balance"))));
        verify(notificationSettingMapper).insert(argThat(setting ->
                Long.valueOf(7L).equals(setting.get("memberId"))
                        && Boolean.TRUE.equals(setting.get("paymentEnabled"))
                        && Boolean.TRUE.equals(setting.get("recurringPaymentEnabled"))
                        && Boolean.TRUE.equals(setting.get("familyShareEnabled"))
                        && Boolean.TRUE.equals(setting.get("communityEnabled"))
                        && Boolean.TRUE.equals(setting.get("marketingEnabled"))));
        verify(authCredentialStore).storeRefresh("7", "refresh-token");
        verify(registrationStore, never()).complete(claim);

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_COMMITTED);
        verify(registrationStore).complete(claim);
        verify(registrationStore, never()).restore(claim);
    }

    @Test
    void unverifiedPhoneRejectsBeforeAnySignupSideEffectAndRestoresClaim() {
        KakaoRegistrationStore.Claim claim = claim(session(null));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(claim);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete(completeRequest(false)));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(memberMapper, never()).insert(any());
        verify(walletMapper, never()).insert(any());
        verify(jwtUtil, never()).generateAccessToken(anyString(), anyString());
        verify(registrationStore).restore(claim);
    }

    @Test
    void completeRechecksProviderEmailPhoneAndInactiveProviderPolicies() {
        assertCompleteConflict(() -> when(memberMapper.findActiveKakaoByProviderId("kakao-id"))
                .thenReturn(Map.of("member_id", 1L)), HttpStatus.CONFLICT);
        assertCompleteConflict(() -> when(memberMapper.findActiveByEmail("member@example.com"))
                .thenReturn(Map.of("member_id", 2L)), HttpStatus.CONFLICT);
        assertCompleteConflict(() -> when(memberMapper.existsActiveByPhone("01012345678"))
                .thenReturn(true), HttpStatus.CONFLICT);
        assertCompleteConflict(() -> when(memberMapper.existsInactiveKakaoByProviderId("kakao-id"))
                .thenReturn(true), HttpStatus.UNAUTHORIZED);

        verify(memberMapper, never()).insert(any());
    }

    @Test
    void memberInsertFailureStopsLaterWorkAndRestoresSession() {
        KakaoRegistrationStore.Claim first = claim(session("01012345678"));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(first);
        stubNoDuplicateMember();
        doThrow(new RuntimeException("member failure")).when(memberMapper).insert(any());
        assertThrows(RuntimeException.class, () -> service.complete(completeRequest(false)));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(registrationStore).restore(first);
        verify(walletMapper, never()).insert(any());
        verify(notificationSettingMapper, never()).insert(any());
        verify(jwtUtil, never()).generateAccessToken(anyString(), anyString());
    }

    @Test
    void duplicateMemberInsertIsConvertedToConflictAndRestoresSession() {
        KakaoRegistrationStore.Claim claim = claim(session("01012345678"));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(claim);
        stubNoDuplicateMember();
        doThrow(new org.springframework.dao.DuplicateKeyException("provider unique"))
                .when(memberMapper).insert(any());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete(completeRequest(false)));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(registrationStore).restore(claim);
        verify(walletMapper, never()).insert(any());
        verify(notificationSettingMapper, never()).insert(any());
    }

    @Test
    void walletInsertFailureStopsNotificationsAndTokensAndRestoresSession() {
        KakaoRegistrationStore.Claim claim = claim(session("01012345678"));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(claim);
        stubNoDuplicateMember();
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 7L);
            return null;
        }).when(memberMapper).insert(any());
        doThrow(new RuntimeException("wallet failure")).when(walletMapper).insert(any());

        assertThrows(RuntimeException.class, () -> service.complete(completeRequest(false)));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(registrationStore).restore(claim);
        verify(notificationSettingMapper, never()).insert(any());
        verify(jwtUtil, never()).generateAccessToken(anyString(), anyString());
        verify(authCredentialStore, never()).storeRefresh(anyString(), anyString());
    }

    @Test
    void notificationInsertFailureStopsTokensAndRestoresSession() {
        KakaoRegistrationStore.Claim claim = claim(session("01012345678"));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(claim);
        stubNoDuplicateMember();
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 7L);
            return null;
        }).when(memberMapper).insert(any());
        doThrow(new RuntimeException("notification failure"))
                .when(notificationSettingMapper).insert(any());

        assertThrows(RuntimeException.class, () -> service.complete(completeRequest(false)));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(walletMapper).insert(any());
        verify(registrationStore).restore(claim);
        verify(jwtUtil, never()).generateAccessToken(anyString(), anyString());
        verify(authCredentialStore, never()).storeRefresh(anyString(), anyString());
    }

    @Test
    void refreshStoreFailureReturns503AndRegistersRollbackCompensation() {
        KakaoRegistrationStore.Claim claim = claim(session("01012345678"));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(claim);
        stubNoDuplicateMember();
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 7L);
            return null;
        }).when(memberMapper).insert(any());
        when(jwtUtil.generateAccessToken("7", "USER")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("7")).thenReturn("refresh-token");
        doThrow(new RuntimeException("redis refresh value"))
                .when(authCredentialStore).storeRefresh("7", "refresh-token");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete(completeRequest(false)));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertFalse(exception.getMessage().contains("redis"));
        verify(authCredentialStore).deleteRefresh("7");
        verify(registrationStore).restore(claim);
    }

    @Test
    void claimedAndSuccessfullyConsumedRegistrationTokenCannotCompleteAgain() {
        when(registrationStore.claim(REGISTRATION_TOKEN))
                .thenThrow(BusinessException.conflict("카카오 가입 요청이 이미 처리 중입니다."));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete(completeRequest(false)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(memberMapper, never()).insert(any());
    }

    @Test
    void completeRequestTypeHasNoClientControlledIdentityOrPhoneFields() {
        assertThrows(NoSuchFieldException.class,
                () -> KakaoSignupCompleteRequest.class.getDeclaredField("phone"));
        assertThrows(NoSuchFieldException.class,
                () -> KakaoSignupCompleteRequest.class.getDeclaredField("email"));
        assertThrows(NoSuchFieldException.class,
                () -> KakaoSignupCompleteRequest.class.getDeclaredField("name"));
        assertThrows(NoSuchFieldException.class,
                () -> KakaoSignupCompleteRequest.class.getDeclaredField("providerId"));
        assertThrows(NoSuchFieldException.class,
                () -> KakaoSignupCompleteRequest.class.getDeclaredField("password"));
    }

    @Test
    void onlyCompleteMethodDeclaresDbTransaction() throws Exception {
        assertTrue(KakaoSignupServiceImpl.class
                .getMethod("complete", KakaoSignupCompleteRequest.class)
                .isAnnotationPresent(Transactional.class));
        assertFalse(KakaoSignupServiceImpl.class
                .getMethod("sendPhoneVerificationCode", KakaoPhoneSendCodeRequest.class)
                .isAnnotationPresent(Transactional.class));
        assertFalse(KakaoSignupServiceImpl.class
                .getMethod("verifyPhoneCode", KakaoPhoneVerifyCodeRequest.class)
                .isAnnotationPresent(Transactional.class));
    }

    private void assertCompleteConflict(Runnable stub, HttpStatus expectedStatus) {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        org.mockito.Mockito.reset(memberMapper, registrationStore);
        KakaoRegistrationStore.Claim claim = claim(session("01012345678"));
        when(registrationStore.claim(REGISTRATION_TOKEN)).thenReturn(claim);
        stubNoDuplicateMember();
        stub.run();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete(completeRequest(false)));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(expectedStatus, exception.getStatus());
        verify(registrationStore).restore(claim);
    }

    private KakaoPhoneSendCodeRequest sendRequest(String phone) {
        KakaoPhoneSendCodeRequest request = new KakaoPhoneSendCodeRequest();
        ReflectionTestUtils.setField(request, "registrationToken", REGISTRATION_TOKEN);
        ReflectionTestUtils.setField(request, "phone", phone);
        return request;
    }

    private KakaoPhoneVerifyCodeRequest verifyRequest(String code) {
        KakaoPhoneVerifyCodeRequest request = new KakaoPhoneVerifyCodeRequest();
        ReflectionTestUtils.setField(request, "registrationToken", REGISTRATION_TOKEN);
        ReflectionTestUtils.setField(request, "verificationCode", code);
        return request;
    }

    private KakaoSignupCompleteRequest completeRequest(boolean marketing) {
        return completeRequest(REGISTRATION_TOKEN, marketing);
    }

    private KakaoSignupCompleteRequest completeRequest(String registrationToken, boolean marketing) {
        KakaoSignupCompleteRequest request = new KakaoSignupCompleteRequest();
        ReflectionTestUtils.setField(request, "registrationToken", registrationToken);
        ReflectionTestUtils.setField(request, "zipCode", "12345");
        ReflectionTestUtils.setField(request, "address", "제주시 애월읍");
        ReflectionTestUtils.setField(request, "addressDetail", "101호");
        ReflectionTestUtils.setField(request, "terms", true);
        ReflectionTestUtils.setField(request, "privacy", true);
        ReflectionTestUtils.setField(request, "marketing", marketing);
        return request;
    }

    private KakaoRegistrationSession session(String verifiedPhone) {
        return new KakaoRegistrationSession(
                "kakao-id", "member@example.com", "홍길동", verifiedPhone);
    }

    private KakaoRegistrationStore.Claim claim(KakaoRegistrationSession session) {
        String original = "session-json";
        return new KakaoRegistrationStore.Claim(
                "registration-key", "CLAIMED|claim-id|" + original, original, session);
    }

    private void stubNoDuplicateMember() {
        lenient().when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        lenient().when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(null);
        lenient().when(memberMapper.existsActiveByPhone("01012345678")).thenReturn(false);
        lenient().when(memberMapper.existsInactiveKakaoByProviderId("kakao-id")).thenReturn(false);
    }
}
