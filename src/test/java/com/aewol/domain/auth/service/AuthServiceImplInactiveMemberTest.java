package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.smtp.EmailService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplInactiveMemberTest {

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

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(memberMapper, walletMapper, notificationSettingMapper,
                jwtUtil, passwordEncoder, redisTemplate, emailService, kakaoAuthClient, authCredentialStore);
        lenient().when(authCredentialStore.storeRefreshIfEpochUnchanged(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        lenient().when(authCredentialStore.getOrCreateEpochForLogin(
                org.mockito.ArgumentMatchers.anyString())).thenReturn("epoch-1");
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
        stubKakaoProfile("member@example.com");
        when(memberMapper.findActiveKakaoByIdentity("member@example.com", "kakao-id"))
                .thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity("member@example.com", "kakao-id"))
                .thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.kakaoLogin("authorization-code"));

        assertEquals(401, exception.getStatus().value());
        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void activeKakaoProviderIdUsesExistingMemberAndTakesPriorityOverInactiveRows() {
        stubKakaoProfile("member@example.com");
        Map<String, Object> activeMember = member(1);
        activeMember.put("member_id", 7L);
        when(memberMapper.findActiveKakaoByIdentity("member@example.com", "kakao-id"))
                .thenReturn(activeMember);
        stubTokenGeneration("7");

        service.kakaoLogin("authorization-code");

        verify(memberMapper, never()).existsInactiveByKakaoIdentity(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(jwtUtil).generateAccessToken("7", "USER", "epoch-1");
        verify(jwtUtil).generateRefreshToken("7");
    }

    @Test
    void inactiveProviderIdWithoutEmailBlocksNewMemberCreation() {
        stubKakaoProfile(null);
        when(memberMapper.findActiveKakaoByIdentity(null, "kakao-id")).thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity(null, "kakao-id")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.kakaoLogin("authorization-code"));

        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void inactiveEmailBlocksNewMemberCreation() {
        stubKakaoProfile("member@example.com");
        when(memberMapper.findActiveKakaoByIdentity("member@example.com", "kakao-id"))
                .thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity("member@example.com", "kakao-id"))
                .thenReturn(true);

        assertThrows(BusinessException.class, () -> service.kakaoLogin("authorization-code"));

        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void activeLocalEmailBlocksNewKakaoMemberCreation() {
        stubKakaoProfile("member@example.com");
        when(memberMapper.findActiveKakaoByIdentity("member@example.com", "kakao-id"))
                .thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity("member@example.com", "kakao-id"))
                .thenReturn(false);
        when(memberMapper.existsActiveByEmail("member@example.com")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.kakaoLogin("authorization-code"));

        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void newKakaoIdentityKeepsExistingSignupAndWalletFlow() {
        stubKakaoProfile("member@example.com");
        when(memberMapper.findActiveKakaoByIdentity("member@example.com", "kakao-id"))
                .thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity("member@example.com", "kakao-id"))
                .thenReturn(false);
        when(memberMapper.existsActiveByEmail("member@example.com")).thenReturn(false);
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 11L);
            return null;
        }).when(memberMapper).insert(org.mockito.ArgumentMatchers.any());
        stubTokenGeneration("11");

        service.kakaoLogin("authorization-code");

        verify(memberMapper).insert(org.mockito.ArgumentMatchers.argThat(member ->
                "KAKAO".equals(member.get("provider")) && "kakao-id".equals(member.get("providerId"))));
        verify(walletMapper).insert(org.mockito.ArgumentMatchers.argThat(wallet ->
                "11".equals(wallet.get("memberId")) && "MAIN".equals(wallet.get("walletType"))));
        verify(jwtUtil).generateAccessToken("11", "USER", "epoch-1");
        verify(jwtUtil).generateRefreshToken("11");
    }

    @Test
    void blankKakaoEmailUsesSyntheticEmailAndNeverUsesBlankIdentity() {
        stubKakaoProfile("   ");
        when(memberMapper.findActiveKakaoByIdentity(null, "kakao-id")).thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity(null, "kakao-id")).thenReturn(false);
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 11L);
            return null;
        }).when(memberMapper).insert(org.mockito.ArgumentMatchers.any());
        stubTokenGeneration("11");

        service.kakaoLogin("authorization-code");

        verify(memberMapper).insert(org.mockito.ArgumentMatchers.argThat(member ->
                "kakao-id@kakao.user".equals(member.get("email"))));
        verify(memberMapper, never()).findActiveKakaoByIdentity("   ", "kakao-id");
        verify(memberMapper, never()).existsInactiveByKakaoIdentity("   ", "kakao-id");
    }

    @Test
    void emptyKakaoEmailAlsoUsesSyntheticEmail() {
        stubKakaoProfile("");
        when(memberMapper.findActiveKakaoByIdentity(null, "kakao-id")).thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity(null, "kakao-id")).thenReturn(false);
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 11L);
            return null;
        }).when(memberMapper).insert(org.mockito.ArgumentMatchers.any());
        stubTokenGeneration("11");

        service.kakaoLogin("authorization-code");

        verify(memberMapper).insert(org.mockito.ArgumentMatchers.argThat(member ->
                "kakao-id@kakao.user".equals(member.get("email"))));
    }

    @Test
    void inactiveLocalEmailIntentionallyBlocksNewKakaoAccountForRecoveryPolicy() {
        stubKakaoProfile("member@example.com");
        when(memberMapper.findActiveKakaoByIdentity("member@example.com", "kakao-id"))
                .thenReturn(null);
        // Mapper의 email arm은 provider와 무관하므로 비활성 LOCAL도 true를 반환한다.
        when(memberMapper.existsInactiveByKakaoIdentity("member@example.com", "kakao-id"))
                .thenReturn(true);

        assertThrows(BusinessException.class, () -> service.kakaoLogin("authorization-code"));

        verifyKakaoAuthenticationWasNotCreated();
    }

    @Test
    void kakaoEmailIsTrimmedBeforeIdentityLookupAndStorage() {
        stubKakaoProfile("  member@example.com  ");
        when(memberMapper.findActiveKakaoByIdentity("member@example.com", "kakao-id")).thenReturn(null);
        when(memberMapper.existsInactiveByKakaoIdentity("member@example.com", "kakao-id")).thenReturn(false);
        when(memberMapper.existsActiveByEmail("member@example.com")).thenReturn(false);
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("memberId", 11L);
            return null;
        }).when(memberMapper).insert(org.mockito.ArgumentMatchers.any());
        stubTokenGeneration("11");

        service.kakaoLogin("authorization-code");

        verify(memberMapper).insert(org.mockito.ArgumentMatchers.argThat(member ->
                "member@example.com".equals(member.get("email"))));
    }

    @Test
    void localLoginEpochChangeBeforeCredentialStoreRejectsIssuance() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", "member@example.com");
        ReflectionTestUtils.setField(request, "password", "password");
        Map<String, Object> active = member(1);
        active.put("member_id", 7L);
        active.put("password", "encoded");
        active.put("email_verified", "Y");
        active.put("role", "USER");
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(active);
        when(authCredentialStore.getOrCreateEpochForLogin("7")).thenReturn("epoch-1");
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtUtil.generateAccessToken("7", "USER", "epoch-1")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("7")).thenReturn("refresh-token");
        when(authCredentialStore.storeRefreshIfEpochUnchanged("7", "epoch-1", "refresh-token"))
                .thenReturn(false);

        assertThrows(BusinessException.class, () -> service.login(request));

        verify(memberMapper, times(2)).findActiveByEmail("member@example.com");
        verify(authCredentialStore).getOrCreateEpochForLogin("7");
    }

    @Test
    void localReloginAfterEpochLossCreatesNewEpochAndIssuesOnlyMatchingTokens() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", "member@example.com");
        ReflectionTestUtils.setField(request, "password", "password");
        Map<String, Object> active = member(1);
        active.put("member_id", 7L);
        active.put("password", "encoded");
        active.put("email_verified", "Y");
        when(memberMapper.findActiveByEmail("member@example.com")).thenReturn(active);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(authCredentialStore.getOrCreateEpochForLogin("7")).thenReturn("epoch-2");
        when(jwtUtil.generateAccessToken("7", "USER", "epoch-2")).thenReturn("a2");
        when(jwtUtil.generateRefreshToken("7")).thenReturn("r2");
        when(authCredentialStore.storeRefreshIfEpochUnchanged("7", "epoch-2", "r2"))
                .thenReturn(true);

        service.login(request);

        verify(jwtUtil).generateAccessToken("7", "USER", "epoch-2");
        verify(jwtUtil, never()).generateAccessToken("7", "USER", "epoch-1");
        verify(authCredentialStore).storeRefreshIfEpochUnchanged("7", "epoch-2", "r2");
    }

    @Test
    void refreshWithoutEpochFailsAndNeverCreatesCredentialGeneration() {
        when(jwtUtil.isTokenValid("r1")).thenReturn(true);
        when(jwtUtil.getSubject("r1")).thenReturn("member-1");
        when(authCredentialStore.getEpoch("member-1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.refresh("r1"));

        verify(authCredentialStore, never()).getOrCreateEpochForLogin(
                org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refreshUsesAtomicEpochAndTokenRotation() {
        when(jwtUtil.isTokenValid("r1")).thenReturn(true);
        when(jwtUtil.getSubject("r1")).thenReturn("member-1");
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-2");
        when(memberMapper.findById("member-1")).thenReturn(member(1));
        when(jwtUtil.generateAccessToken("member-1", "USER", "epoch-2")).thenReturn("a2");
        when(jwtUtil.generateRefreshToken("member-1")).thenReturn("r2");
        when(authCredentialStore.rotateRefreshAtomically("member-1", "epoch-2", "r1", "r2"))
                .thenReturn(true);

        service.refresh("r1");

        verify(authCredentialStore).rotateRefreshAtomically("member-1", "epoch-2", "r1", "r2");
    }

    @Test
    void refreshEpochRaceReturnsNoTokenPair() {
        when(jwtUtil.isTokenValid("r1")).thenReturn(true);
        when(jwtUtil.getSubject("r1")).thenReturn("member-1");
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-1");
        when(memberMapper.findById("member-1")).thenReturn(member(1));
        when(jwtUtil.generateAccessToken("member-1", "USER", "epoch-1")).thenReturn("a2");
        when(jwtUtil.generateRefreshToken("member-1")).thenReturn("r2");
        when(authCredentialStore.rotateRefreshAtomically("member-1", "epoch-1", "r1", "r2"))
                .thenReturn(false);

        assertThrows(BusinessException.class, () -> service.refresh("r1"));
    }

    @Test
    void refreshRejectsInactiveMemberWithoutRotatingToken() {
        when(jwtUtil.isTokenValid("refresh-token")).thenReturn(true);
        when(jwtUtil.getSubject("refresh-token")).thenReturn("member-1");
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-1");
        when(memberMapper.findById("member-1")).thenReturn(member(0));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refresh("refresh-token"));

        assertEquals(401, exception.getStatus().value());
        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
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
        when(jwtUtil.getSubject("refresh-token")).thenReturn("member-1");
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-1");
        when(memberMapper.findById("member-1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.refresh("refresh-token"));

        verify(authCredentialStore, never()).rotateRefreshAtomically(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(valueOperations, never()).set(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
    }

    private void stubKakaoProfile(String email) {
        when(kakaoAuthClient.getAccessToken("authorization-code"))
                .thenReturn(Map.of("access_token", "kakao-access-token"));
        Map<String, Object> kakaoAccount = new HashMap<>();
        if (email != null) {
            kakaoAccount.put("email", email);
        }
        kakaoAccount.put("profile", Map.of("nickname", "nickname"));
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", "kakao-id");
        profile.put("kakao_account", kakaoAccount);
        when(kakaoAuthClient.getUserProfile("kakao-access-token")).thenReturn(profile);
    }

    private void stubTokenGeneration(String memberId) {
        when(jwtUtil.generateAccessToken(memberId, "USER", "epoch-1")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(memberId)).thenReturn("refresh-token");
    }

    private void verifyKakaoAuthenticationWasNotCreated() {
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(walletMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(jwtUtil, never()).generateAccessToken(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(jwtUtil, never()).generateRefreshToken(org.mockito.ArgumentMatchers.anyString());
        verify(redisTemplate, never()).opsForValue();
    }

    private Map<String, Object> member(int active) {
        Map<String, Object> member = new HashMap<>();
        member.put("is_active", active);
        member.put("role", "USER");
        return member;
    }
}
