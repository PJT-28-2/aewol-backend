package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.dto.KakaoAuthStatus;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.kakao.KakaoUserInfo;
import com.aewol.external.smtp.EmailService;
import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplInactiveMemberTest {

    @Mock MemberMapper memberMapper;
    @Mock WalletMapper walletMapper;
    @Mock NotificationSettingMapper notificationSettingMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock EmailService emailService;
    @Mock KakaoAuthClient kakaoAuthClient;
    @Mock AuthCredentialStore authCredentialStore;
    @Mock KakaoRegistrationStore kakaoRegistrationStore;
    @Mock TransactionOperations transactionOperations;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(memberMapper, walletMapper, notificationSettingMapper,
                jwtUtil, passwordEncoder, redisTemplate, redisRateLimiter, emailService,
                kakaoAuthClient, authCredentialStore, kakaoRegistrationStore,
                transactionOperations);
        lenient().when(transactionOperations.execute(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
    }

    @Test
    void localLoginRejectsWhenNoActiveMemberExists() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", "member@example.com");
        ReflectionTestUtils.setField(request, "password", "password");
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.login(request));

        assertEquals(401, exception.getStatus().value());
        verify(passwordEncoder, never()).matches("password", null);
    }

    @Test
    void inactiveKakaoIdentityBlocksTokenAndNewMemberCreation() {
        stubKakaoUserInfo("member@example.com", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        when(memberMapper.existsInactiveKakaoByProviderId("kakao-id")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.kakaoLogin("authorization-code"));

        assertEquals(401, exception.getStatus().value());
        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void recoverableKakaoIdentityRestoresExistingMemberAndIssuesFreshTokens() {
        stubKakaoUserInfo("member@example.com", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(null);
        Map<String, Object> inactive = recoverableInactiveKakaoMember();
        when(memberMapper.findInactiveKakaoByProviderIdForUpdate("kakao-id"))
                .thenReturn(inactive);
        when(memberMapper.restoreKakaoMember(7L)).thenReturn(1);
        stubTokenGeneration("7");

        KakaoOAuthResponse response = service.kakaoLogin("authorization-code");

        assertEquals(KakaoAuthStatus.ACCOUNT_RESTORED, response.getAuthStatus());
        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        verify(memberMapper).restoreKakaoMember(7L);
        verify(notificationSettingMapper).ensureForRecovery(7L);
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(walletMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(kakaoRegistrationStore, never()).create(org.mockito.ArgumentMatchers.any());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                kakaoAuthClient, transactionOperations);
        order.verify(kakaoAuthClient).getAccessToken("authorization-code");
        order.verify(kakaoAuthClient).getUserInfo("kakao-access-token");
        order.verify(transactionOperations).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recoverableKakaoIdentityRejectsNullRoleBeforeRestoring() {
        assertInvalidRecoveryRole(null);
    }

    @Test
    void recoverableKakaoIdentityRejectsBlankRoleBeforeRestoring() {
        assertInvalidRecoveryRole("   ");
    }

    @Test
    void recoverableKakaoIdentityRejectsUnknownRoleBeforeRestoring() {
        assertInvalidRecoveryRole("SUPERUSER");
    }

    @Test
    void kakaoIdentityAtExactlyThirtyDayBoundaryIsRecoverable() {
        stubKakaoUserInfo("member@example.com", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(null);
        Map<String, Object> boundaryMember = recoverableInactiveKakaoMember();
        when(memberMapper.findInactiveKakaoByProviderIdForUpdate("kakao-id"))
                .thenReturn(boundaryMember);
        when(memberMapper.restoreKakaoMember(7L)).thenReturn(1);
        stubTokenGeneration("7");

        KakaoOAuthResponse response = service.kakaoLogin("authorization-code");

        assertEquals(KakaoAuthStatus.ACCOUNT_RESTORED, response.getAuthStatus());
        verify(memberMapper).restoreKakaoMember(7L);
        verify(kakaoRegistrationStore, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void kakaoIdentityPastThirtyDaysRemainsBlocked() {
        stubKakaoUserInfo("member@example.com", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(null);
        when(memberMapper.findInactiveKakaoByProviderIdForUpdate("kakao-id"))
                .thenReturn(null);
        when(memberMapper.existsInactiveKakaoByProviderId("kakao-id")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.kakaoLogin("authorization-code"));

        assertEquals(401, exception.getStatus().value());
        verify(memberMapper, never()).restoreKakaoMember(org.mockito.ArgumentMatchers.anyLong());
        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void activeKakaoProviderIdLogsInWithoutRegistrationSessionOrNewData() {
        stubKakaoUserInfo("member@example.com", "홍길동");
        Map<String, Object> activeMember = member(1);
        activeMember.put("member_id", 7L);
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(activeMember);
        stubTokenGeneration("7");

        KakaoOAuthResponse response = service.kakaoLogin("authorization-code");

        assertEquals(KakaoAuthStatus.LOGIN_COMPLETE, response.getAuthStatus());
        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertNull(response.getRegistrationToken());
        verify(memberMapper, never()).existsInactiveKakaoByProviderId(
                org.mockito.ArgumentMatchers.anyString());
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(walletMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(notificationSettingMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(jwtUtil).generateAccessToken("7", "USER");
        verify(jwtUtil).generateRefreshToken("7");
        verify(authCredentialStore).storeRefresh("7", "refresh-token");
        verify(kakaoRegistrationStore, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newKakaoIdentityCreatesOnlyRegistrationSession() {
        stubKakaoUserInfo("member@example.com", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(null);
        when(memberMapper.existsInactiveKakaoByProviderId("kakao-id")).thenReturn(false);
        when(kakaoRegistrationStore.create(org.mockito.ArgumentMatchers.any()))
                .thenReturn("registration-token");

        KakaoOAuthResponse response = service.kakaoLogin("authorization-code");

        assertEquals(KakaoAuthStatus.ADDITIONAL_INFO_REQUIRED, response.getAuthStatus());
        assertNull(response.getAccessToken());
        assertNull(response.getRefreshToken());
        assertEquals("registration-token", response.getRegistrationToken());
        verify(kakaoRegistrationStore).create(org.mockito.ArgumentMatchers.argThat(session ->
                "kakao-id".equals(session.getProviderId())
                        && "member@example.com".equals(session.getEmail())
                        && "홍길동".equals(session.getName())));
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(walletMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(notificationSettingMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(authCredentialStore, never()).storeRefresh(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void syntheticKakaoEmailIsStoredOnlyInRegistrationSession() {
        stubKakaoUserInfo("kakao-id@kakao.user", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        when(kakaoRegistrationStore.create(org.mockito.ArgumentMatchers.any()))
                .thenReturn("registration-token");

        service.kakaoLogin("authorization-code");

        verify(memberMapper).findActiveByEmail("kakao-id@kakao.user");
        verify(kakaoRegistrationStore).create(org.mockito.ArgumentMatchers.argThat(session ->
                "kakao-id@kakao.user".equals(session.getEmail())));
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeLocalEmailConflictDoesNotCreateRegistrationOrAuthentication() {
        stubKakaoUserInfo("member@example.com", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        Map<String, Object> localMember = member(1);
        localMember.put("provider", "LOCAL");
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(localMember);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.kakaoLogin("authorization-code"));

        assertEquals(409, exception.getStatus().value());
        assertEquals("이미 가입된 이메일입니다.", exception.getMessage());
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void invalidKakaoProfileFailsBeforeRegistrationSessionCreation() {
        when(kakaoAuthClient.getAccessToken("authorization-code"))
                .thenReturn("kakao-access-token");
        when(kakaoAuthClient.getUserInfo("kakao-access-token"))
                .thenThrow(new BusinessException("카카오 사용자 정보를 확인할 수 없습니다."));

        assertThrows(BusinessException.class,
                () -> service.kakaoLogin("authorization-code"));

        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void localLoginStoresRefreshAndReturnsTokens() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", "member@example.com");
        ReflectionTestUtils.setField(request, "password", "password");
        Map<String, Object> active = member(1);
        active.put("member_id", 7L);
        active.put("password", "encoded");
        active.put("email_verified", "Y");
        active.put("role", "USER");
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(active);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtUtil.generateAccessToken("7", "USER")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("7")).thenReturn("refresh-token");

        service.login(request);

        verify(memberMapper).findActiveByEmail("member@example.com");
        verify(authCredentialStore).storeRefresh("7", "refresh-token");
    }

    @Test
    void localReloginIssuesNewTokenPair() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", "member@example.com");
        ReflectionTestUtils.setField(request, "password", "password");
        Map<String, Object> active = member(1);
        active.put("member_id", 7L);
        active.put("password", "encoded");
        active.put("email_verified", "Y");
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(active);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtUtil.generateAccessToken("7", "USER")).thenReturn("a2");
        when(jwtUtil.generateRefreshToken("7")).thenReturn("r2");

        service.login(request);

        verify(jwtUtil).generateAccessToken("7", "USER");
        verify(authCredentialStore).storeRefresh("7", "r2");
    }

    @Test
    void refreshWithoutIssuedAtFailsClosed() {
        when(jwtUtil.isTokenValid("r1")).thenReturn(true);
        Claims claims = refreshClaims(null);
        when(jwtUtil.parseClaims("r1")).thenReturn(claims);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(1));

        assertThrows(BusinessException.class, () -> service.refresh("r1"));

        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refreshUsesAtomicTokenRotation() {
        when(jwtUtil.isTokenValid("r1")).thenReturn(true);
        Claims claims = refreshClaims(new Date(1_001_000L));
        when(jwtUtil.parseClaims("r1")).thenReturn(claims);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(1, 1_000L));
        when(jwtUtil.generateAccessToken("member-1", "USER")).thenReturn("a2");
        when(jwtUtil.generateRefreshToken("member-1")).thenReturn("r2");
        when(authCredentialStore.rotateRefreshAtomically("member-1", "r1", "r2"))
                .thenReturn(true);

        service.refresh("r1");

        verify(authCredentialStore).rotateRefreshAtomically("member-1", "r1", "r2");
    }

    @Test
    void reusedRefreshReturnsNoTokenPair() {
        when(jwtUtil.isTokenValid("r1")).thenReturn(true);
        Claims claims = refreshClaims(new Date(1_001_000L));
        when(jwtUtil.parseClaims("r1")).thenReturn(claims);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(1, 1_000L));
        when(jwtUtil.generateAccessToken("member-1", "USER")).thenReturn("a2");
        when(jwtUtil.generateRefreshToken("member-1")).thenReturn("r2");
        when(authCredentialStore.rotateRefreshAtomically("member-1", "r1", "r2"))
                .thenReturn(false);

        assertThrows(BusinessException.class, () -> service.refresh("r1"));
    }

    @Test
    void refreshRejectsInactiveMemberWithoutRotatingToken() {
        when(jwtUtil.isTokenValid("refresh-token")).thenReturn(true);
        Claims claims = refreshClaims(new Date(1_001_000L));
        when(jwtUtil.parseClaims("refresh-token")).thenReturn(claims);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(0, 1_000L));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refresh("refresh-token"));

        assertEquals(401, exception.getStatus().value());
        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(valueOperations, never()).set(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
    }

    @Test
    void refreshRejectsMissingMemberWithoutRotatingToken() {
        when(jwtUtil.isTokenValid("refresh-token")).thenReturn(true);
        Claims claims = refreshClaims(new Date(1_001_000L));
        when(jwtUtil.parseClaims("refresh-token")).thenReturn(claims);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.refresh("refresh-token"));

        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(valueOperations, never()).set(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
    }

    private void stubKakaoUserInfo(String email, String name) {
        when(kakaoAuthClient.getAccessToken("authorization-code"))
                .thenReturn("kakao-access-token");
        when(kakaoAuthClient.getUserInfo("kakao-access-token"))
                .thenReturn(new KakaoUserInfo("kakao-id", email, name));
    }

    private void stubTokenGeneration(String memberId) {
        when(jwtUtil.generateAccessToken(memberId, "USER")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(memberId)).thenReturn("refresh-token");
    }

    private void verifyKakaoAuthenticationWasNotCreated() {
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(walletMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(notificationSettingMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(authCredentialStore, never()).storeRefresh(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(kakaoRegistrationStore, never()).create(org.mockito.ArgumentMatchers.any());
    }

    private Map<String, Object> member(int active) {
        Map<String, Object> member = new HashMap<>();
        member.put("is_active", active);
        member.put("role", "USER");
        return member;
    }

    @Test
    void recoveredMemberRejectsRefreshIssuedAtOrBeforeWithdrawal() {
        when(jwtUtil.isTokenValid("old-refresh")).thenReturn(true);
        Claims before = refreshClaims(new Date(999_000L));
        Claims equal = refreshClaims(new Date(1_000_000L));
        when(jwtUtil.parseClaims("old-refresh")).thenReturn(before, equal);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(1, 1_000L));

        assertThrows(BusinessException.class, () -> service.refresh("old-refresh"));
        assertThrows(BusinessException.class, () -> service.refresh("old-refresh"));

        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void accessTokenCannotBeUsedForRefresh() {
        assertWrongTokenTypeFailsBeforeRefreshWork("access-token");
    }

    @Test
    void tokenWithoutTypeCannotBeUsedForRefresh() {
        assertWrongTokenTypeFailsBeforeRefreshWork("legacy-token");
    }

    @Test
    void unknownTokenTypeCannotBeUsedForRefresh() {
        assertWrongTokenTypeFailsBeforeRefreshWork("unknown-token");
    }

    private Map<String, Object> member(int active, Long withdrawnAtEpoch) {
        Map<String, Object> member = member(active);
        member.put("withdrawn_at_epoch", withdrawnAtEpoch);
        return member;
    }

    private Map<String, Object> recoverableInactiveKakaoMember() {
        Map<String, Object> member = member(0, 1_000L);
        member.put("member_id", 7L);
        member.put("provider", "KAKAO");
        member.put("provider_id", "kakao-id");
        member.put("email", "member@example.com");
        member.put("recoverable_within_30_days", 1);
        return member;
    }

    private void assertInvalidRecoveryRole(String role) {
        stubKakaoUserInfo("member@example.com", "홍길동");
        when(memberMapper.findActiveKakaoByProviderId("kakao-id")).thenReturn(null);
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(null);
        Map<String, Object> inactive = recoverableInactiveKakaoMember();
        inactive.put("role", role);
        when(memberMapper.findInactiveKakaoByProviderIdForUpdate("kakao-id"))
                .thenReturn(inactive);

        assertThrows(IllegalStateException.class,
                () -> service.kakaoLogin("authorization-code"));

        verify(memberMapper, never()).restoreKakaoMember(
                org.mockito.ArgumentMatchers.anyLong());
        verify(notificationSettingMapper, never()).ensureForRecovery(
                org.mockito.ArgumentMatchers.anyLong());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private Claims refreshClaims(Date issuedAt) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.getSubject()).thenReturn("member-1");
        when(claims.getIssuedAt()).thenReturn(issuedAt);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        return claims;
    }

    private void assertWrongTokenTypeFailsBeforeRefreshWork(String token) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.isTokenValid(token)).thenReturn(true);
        when(jwtUtil.parseClaims(token)).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.refresh(token));

        verify(memberMapper, never()).findAuthStateById(org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
