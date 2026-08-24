package com.aewol.domain.auth.service;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.smtp.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLogoutTest {

    @Mock MemberMapper memberMapper;
    @Mock WalletMapper walletMapper;
    @Mock NotificationSettingMapper notificationSettingMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock RedisRateLimiter redisRateLimiter;
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
                transactionOperations,
                MemberAuthStateCache.withoutCache(memberMapper));
    }

    @Test
    void logoutDeletesRefreshTokenForAuthenticatedMember() {
        service.logout("member-1");

        verify(authCredentialStore).deleteRefresh("member-1");
    }

    @Test
    void logoutReturnsServiceUnavailableWhenRefreshDeleteFails() {
        doThrow(new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "인증 서비스를 일시적으로 사용할 수 없습니다."))
                .when(authCredentialStore).deleteRefresh("member-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.logout("member-1"));

        assertEquals(503, exception.getStatus().value());
        assertEquals(null, exception.getErrorCode());
    }
}
