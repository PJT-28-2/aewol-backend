package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.common.util.PhoneNumberUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.common.util.Sha256Util;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeRequest;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeResponse;
import com.aewol.domain.auth.dto.KakaoPhoneVerifyCodeRequest;
import com.aewol.domain.auth.dto.KakaoRegistrationSession;
import com.aewol.domain.auth.dto.KakaoSignupCompleteRequest;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.sms.SmsSender;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoSignupServiceImpl implements KakaoSignupService {

    static final long RATE_LIMIT_WINDOW_SECONDS = 1800L;
    static final long RATE_LIMIT_MAX = 5L;
    static final String PHONE_RATE_LIMIT_PREFIX =
            "kakao:registration:phone:request-count:phone:";
    static final String TOKEN_RATE_LIMIT_PREFIX =
            "kakao:registration:phone:request-count:token:";
    private static final String SMS_FAILURE_MESSAGE =
            "인증번호 발송에 실패했습니다. 잠시 후 다시 시도해주세요.";
    private static final String VERIFICATION_SERVICE_FAILURE_MESSAGE =
            "전화번호 인증 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.";

    private final MemberMapper memberMapper;
    private final WalletMapper walletMapper;
    private final NotificationSettingMapper notificationSettingMapper;
    private final JwtUtil jwtUtil;
    private final AuthCredentialStore authCredentialStore;
    private final KakaoRegistrationStore registrationStore;
    private final KakaoPhoneVerificationStore phoneVerificationStore;
    private final RedisRateLimiter redisRateLimiter;
    private final SmsSender smsSender;

    @Override
    public KakaoPhoneSendCodeResponse sendPhoneVerificationCode(
            KakaoPhoneSendCodeRequest request) {
        registrationStore.getAvailable(request.getRegistrationToken());
        String normalizedPhone = PhoneNumberUtil.normalize(request.getPhone());
        if (normalizedPhone == null || !normalizedPhone.matches("^010\\d{8}$")) {
            throw new BusinessException("올바른 휴대전화 번호를 입력해주세요.");
        }
        if (memberMapper.existsActiveByPhone(normalizedPhone)) {
            throw BusinessException.conflict("이미 사용 중인 전화번호입니다.");
        }

        String tokenHash = subjectHash(request.getRegistrationToken());
        String phoneHash = subjectHash(normalizedPhone);
        enforceRateLimit(TOKEN_RATE_LIMIT_PREFIX + tokenHash);
        enforceRateLimit(PHONE_RATE_LIMIT_PREFIX + phoneHash);

        KakaoPhoneVerificationStore.IssuedVerification issued =
                phoneVerificationStore.issue(
                        registrationStore.redisKey(request.getRegistrationToken()),
                        tokenHash,
                        normalizedPhone);
        try {
            smsSender.send(normalizedPhone,
                    "[AeWol] 카카오 회원가입 인증번호: " + issued.getCode()
                            + " (5분 이내 입력)");
        } catch (RuntimeException e) {
            try {
                phoneVerificationStore.discard(issued);
            } catch (RuntimeException ignored) {
                // 오류 메시지나 Redis key를 로그에 남기지 않고 OTP TTL 만료에 맡긴다.
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, SMS_FAILURE_MESSAGE);
        }
        return new KakaoPhoneSendCodeResponse(issued.getExpiresInSeconds());
    }

    @Override
    public void verifyPhoneCode(KakaoPhoneVerifyCodeRequest request) {
        registrationStore.getAvailable(request.getRegistrationToken());
        String tokenHash = subjectHash(request.getRegistrationToken());
        String verifiedPhone = phoneVerificationStore.verify(
                tokenHash, request.getVerificationCode());
        registrationStore.updateVerifiedPhone(request.getRegistrationToken(), verifiedPhone);
        phoneVerificationStore.consumeVerified(tokenHash, verifiedPhone);
    }

    @Override
    @Transactional
    public KakaoOAuthResponse complete(KakaoSignupCompleteRequest request) {
        KakaoRegistrationStore.Claim claim = registrationStore.claim(request.getRegistrationToken());
        registerClaimCompletion(claim);

        KakaoRegistrationSession session = claim.getSession();
        String verifiedPhone = session.getVerifiedPhone();
        if (!StringUtils.hasText(verifiedPhone) || !verifiedPhone.matches("^010\\d{8}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "전화번호 인증이 완료되지 않았거나 만료되었습니다.");
        }

        if (memberMapper.findActiveKakaoByProviderId(session.getProviderId()) != null) {
            throw BusinessException.conflict("이미 가입된 카카오 계정입니다.");
        }
        if (memberMapper.existsInactiveKakaoByProviderId(session.getProviderId())) {
            throw BusinessException.unauthorized("카카오 로그인에 실패했습니다.");
        }
        if (memberMapper.findActiveByEmail(session.getEmail()) != null) {
            throw BusinessException.conflict("이미 가입된 이메일입니다.");
        }
        if (memberMapper.existsActiveByPhone(verifiedPhone)) {
            throw BusinessException.conflict("이미 사용 중인 전화번호입니다.");
        }

        Map<String, Object> member = memberValues(session, request);
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("이미 가입된 회원입니다.");
        }
        Long memberId = generatedMemberId(member);

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("memberId", memberId);
        wallet.put("walletType", "MAIN");
        wallet.put("balance", 0);
        walletMapper.insert(wallet);

        notificationSettingMapper.insert(
                notificationSettingValues(memberId, request.isMarketing()));

        String memberIdValue = String.valueOf(memberId);
        String accessToken = jwtUtil.generateAccessToken(memberIdValue, "USER");
        String refreshToken = jwtUtil.generateRefreshToken(memberIdValue);
        registerRefreshRollback(memberIdValue);
        try {
            authCredentialStore.storeRefresh(memberIdValue, refreshToken);
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "카카오 가입을 완료할 수 없습니다. 잠시 후 다시 시도해주세요.");
        }

        return KakaoOAuthResponse.loginComplete(TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build());
    }

    private void enforceRateLimit(String key) {
        long count;
        try {
            count = redisRateLimiter.incrementWithExpiry(key, RATE_LIMIT_WINDOW_SECONDS);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    VERIFICATION_SERVICE_FAILURE_MESSAGE);
        }
        if (count > RATE_LIMIT_MAX) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "카카오 회원가입 인증번호 요청이 너무 많습니다. 30분 후 다시 시도해주세요.");
        }
    }

    private String subjectHash(String value) {
        return Sha256Util.lowercaseHex(value);
    }

    private Map<String, Object> memberValues(
            KakaoRegistrationSession session,
            KakaoSignupCompleteRequest request) {
        Map<String, Object> member = new HashMap<>();
        member.put("email", session.getEmail());
        member.put("password", null);
        member.put("name", session.getName());
        member.put("phone", session.getVerifiedPhone());
        member.put("provider", "KAKAO");
        member.put("providerId", session.getProviderId());
        member.put("emailVerified", "Y");
        member.put("role", "USER");
        member.put("profileImg", null);
        member.put("zipCode", request.getZipCode());
        member.put("address", request.getAddress());
        member.put("addressDetail", request.getAddressDetail());
        return member;
    }

    private Map<String, Object> notificationSettingValues(
            Long memberId,
            boolean marketingEnabled) {
        Map<String, Object> setting = new HashMap<>();
        setting.put("memberId", memberId);
        setting.put("paymentEnabled", true);
        setting.put("recurringPaymentEnabled", true);
        setting.put("familyShareEnabled", true);
        setting.put("communityEnabled", true);
        setting.put("marketingEnabled", marketingEnabled);
        return setting;
    }

    private Long generatedMemberId(Map<String, Object> member) {
        Object memberId = member.get("memberId");
        if (!(memberId instanceof Number)) {
            throw new IllegalStateException("생성된 회원 ID를 확인할 수 없습니다.");
        }
        return ((Number) memberId).longValue();
    }

    private void registerClaimCompletion(KakaoRegistrationStore.Claim claim) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                registrationStore.restore(claim);
            } catch (RuntimeException ignored) {
                // 민감한 가입 세션 식별자를 로그에 남기지 않는다.
            }
            throw new IllegalStateException("카카오 가입 트랜잭션 동기화가 활성화되지 않았습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    registrationStore.complete(claim);
                } catch (RuntimeException e) {
                    log.warn("카카오 가입 완료 후 registration session 삭제에 실패했습니다. TTL 만료를 기다립니다.");
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    registrationStore.restore(claim);
                } catch (RuntimeException e) {
                    log.warn("카카오 가입 rollback 후 registration session 복원에 실패했습니다.");
                }
            }
        });
    }

    private void registerRefreshRollback(String memberId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    authCredentialStore.deleteRefresh(memberId);
                } catch (RuntimeException e) {
                    log.warn("카카오 가입 rollback 후 Refresh Token 정리에 실패했습니다. TTL 만료를 기다립니다.");
                }
            }
        });
    }

}
