package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.common.util.PhoneNumberUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.KakaoRegistrationSession;
import com.aewol.domain.auth.dto.PasswordResetEmailRequest;
import com.aewol.domain.auth.dto.PasswordResetRequest;
import com.aewol.domain.auth.dto.PasswordResetVerifyRequest;
import com.aewol.domain.auth.dto.PasswordResetVerifyResponse;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.kakao.KakaoUserInfo;
import com.aewol.external.smtp.EmailService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long SIGNUP_VERIFICATION_TTL_SECONDS = 300L;
    private static final String SIGNUP_VERIFICATION_CODE_KEY_PREFIX = "signup:verify:";
    private static final String SIGNUP_VERIFICATION_COMPLETED_KEY_PREFIX = "signup:verify:completed:";
    private static final long PASSWORD_RESET_VERIFICATION_TTL_SECONDS = 300L;
    private static final long PASSWORD_RESET_TOKEN_TTL_SECONDS = 300L;
    private static final int PASSWORD_RESET_MAX_VERIFICATION_ATTEMPTS = 5;
    private static final String PASSWORD_RESET_REQUEST_RATE_LIMIT_KEY_PREFIX =
            "password:reset:request-count:";
    private static final long PASSWORD_RESET_REQUEST_RATE_LIMIT_WINDOW_SECONDS = 1800L;
    private static final long PASSWORD_RESET_REQUEST_RATE_LIMIT_MAX = 5L;
    private static final String PASSWORD_RESET_VERIFICATION_KEY_PREFIX = "password:reset:verify:";
    private static final String PASSWORD_RESET_TOKEN_KEY_PREFIX = "password:reset:token:";
    private static final String INVALID_PASSWORD_RESET_TOKEN_MESSAGE =
            "유효하지 않거나 만료된 비밀번호 재설정 토큰입니다.";
    private static final String VERIFICATION_VALUE_DELIMITER = "|";
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = new DefaultRedisScript<>(
            "local stored = redis.call('GET', KEYS[1])\n" +
            "if stored == ARGV[1] then\n" +
            "    return redis.call('DEL', KEYS[1])\n" +
            "end\n" +
            "return 0",
            Long.class);
    private static final DefaultRedisScript<Long> VERIFY_CODE_SCRIPT = new DefaultRedisScript<>(
            "local stored = redis.call('GET', KEYS[1])\n" +
            "if not stored then\n" +
            "    return 0\n" +
            "end\n" +
            "local delimiter = string.find(stored, '|', 1, true)\n" +
            "if not delimiter then\n" +
            "    return -1\n" +
            "end\n" +
            "local requestId = string.sub(stored, 1, delimiter - 1)\n" +
            "local code = string.sub(stored, delimiter + 1)\n" +
            "if string.len(requestId) ~= 36\n" +
            "        or string.sub(requestId, 9, 9) ~= '-'\n" +
            "        or string.sub(requestId, 14, 14) ~= '-'\n" +
            "        or string.sub(requestId, 19, 19) ~= '-'\n" +
            "        or string.sub(requestId, 24, 24) ~= '-'\n" +
            "        or string.find(requestId, '[^0-9a-fA-F%-]')\n" +
            "        or string.len(code) ~= 6\n" +
            "        or string.find(code, '%D') then\n" +
            "    return -1\n" +
            "end\n" +
            "if code ~= ARGV[1] then\n" +
            "    return -1\n" +
            "end\n" +
            "redis.call('DEL', KEYS[1])\n" +
            // requestId를 클라이언에 노출하지 않고도 최종 가입이 같은 인증 결과를 소비하는지 확인할 수 있게 전체 값을 유지한다.
            "redis.call('SET', KEYS[2], stored, 'EX', ARGV[2])\n" +
            "return 1",
            Long.class);
    private static final DefaultRedisScript<Long> VERIFY_PASSWORD_RESET_CODE_SCRIPT = new DefaultRedisScript<>(
            "local stored = redis.call('GET', KEYS[1])\n" +
            "if not stored then return 0 end\n" +
            "local requestId, code, attempts = string.match(stored, '^([0-9a-fA-F%-]+)|(%d%d%d%d%d%d)|(%d+)$')\n" +
            "if not requestId or string.len(requestId) ~= 36 then return -1 end\n" +
            "if code ~= ARGV[1] then\n" +
            "    attempts = tonumber(attempts) + 1\n" +
            "    if attempts >= tonumber(ARGV[2]) then\n" +
            "        redis.call('DEL', KEYS[1])\n" +
            "    else\n" +
            "        redis.call('SET', KEYS[1], requestId .. '|' .. code .. '|' .. attempts, 'KEEPTTL')\n" +
            "    end\n" +
            "    return -1\n" +
            "end\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "local created = redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4], 'NX')\n" +
            "if not created then return -2 end\n" +
            "return 1",
            Long.class);
    private static final String PASSWORD_RESET_TOKEN_CLAIM_PREFIX = "CLAIMED|";
    private static final DefaultRedisScript<String> CLAIM_PASSWORD_RESET_TOKEN_SCRIPT =
            new DefaultRedisScript<>(
                    "local stored = redis.call('GET', KEYS[1])\n" +
                    "if not stored or string.sub(stored, 1, 8) == 'CLAIMED|' then return nil end\n" +
                    "redis.call('SET', KEYS[1], ARGV[1] .. stored, 'KEEPTTL')\n" +
                    "return stored",
                    String.class);
    private static final DefaultRedisScript<Long> COMPARE_AND_RESTORE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end\n" +
            "redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')\n" +
            "return 1",
            Long.class);

    private final MemberMapper memberMapper;
    private final WalletMapper walletMapper;
    private final NotificationSettingMapper notificationSettingMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisRateLimiter redisRateLimiter;
    private final EmailService emailService;
    private final KakaoAuthClient kakaoAuthClient;
    private final AuthCredentialStore authCredentialStore;
    private final KakaoRegistrationStore kakaoRegistrationStore;

    @Override
    public SignupEmailCodeResponse sendSignupVerificationCode(SignupEmailCodeRequest request) {
        String email = request.getEmail();
        if (memberMapper.existsActiveByEmail(email)) {
            throw BusinessException.conflict("이미 사용 중인 이메일입니다.");
        }

        String code = generateVerificationCode();
        String verificationValue = UUID.randomUUID() + VERIFICATION_VALUE_DELIMITER + code;
        String verificationKey = verificationCodeKey(email);
        redisTemplate.opsForValue().set(
                verificationKey, verificationValue, SIGNUP_VERIFICATION_TTL_SECONDS, TimeUnit.SECONDS);

        try {
            emailService.sendVerificationEmail(email, code);
        } catch (RuntimeException e) {
            try {
                redisTemplate.execute(
                        COMPARE_AND_DELETE_SCRIPT, List.of(verificationKey), verificationValue);
            } catch (RuntimeException cleanupException) {
                e.addSuppressed(cleanupException);
            }
            throw e;
        }

        return new SignupEmailCodeResponse(SIGNUP_VERIFICATION_TTL_SECONDS);
    }

    @Override
    public void verifySignupEmailCode(SignupEmailVerificationRequest request) {
        String email = request.getEmail();
        String verificationKey = verificationCodeKey(email);
        Long result = redisTemplate.execute(
                VERIFY_CODE_SCRIPT,
                List.of(verificationKey, verificationCompletedKey(email)),
                request.getVerificationCode(), String.valueOf(SIGNUP_VERIFICATION_TTL_SECONDS));

        if (result == null || result == 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증번호가 만료되었거나 발급되지 않았습니다.");
        }
        if (result == -1L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다.");
        }
    }

    @Override
    public SignupEmailCodeResponse sendPasswordResetVerificationCode(PasswordResetEmailRequest request) {
        String rateLimitEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        long requestCount = redisRateLimiter.incrementWithExpiry(
                PASSWORD_RESET_REQUEST_RATE_LIMIT_KEY_PREFIX + rateLimitEmail,
                PASSWORD_RESET_REQUEST_RATE_LIMIT_WINDOW_SECONDS);
        if (requestCount > PASSWORD_RESET_REQUEST_RATE_LIMIT_MAX) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "비밀번호 재설정 요청이 너무 많아요. 30분 후 다시 시도해주세요");
        }

        Map<String, Object> member = memberMapper.findActiveByEmail(request.getEmail());
        if (!isActiveLocalMember(member)) {
            return new SignupEmailCodeResponse(PASSWORD_RESET_VERIFICATION_TTL_SECONDS);
        }

        String code = generateVerificationCode();
        String verificationValue = UUID.randomUUID() + VERIFICATION_VALUE_DELIMITER + code
                + VERIFICATION_VALUE_DELIMITER + "0";
        String verificationKey = passwordResetVerificationKey(request.getEmail());
        redisTemplate.opsForValue().set(
                verificationKey,
                verificationValue,
                PASSWORD_RESET_VERIFICATION_TTL_SECONDS,
                TimeUnit.SECONDS);

        try {
            emailService.sendPasswordResetEmail(request.getEmail(), code);
        } catch (RuntimeException e) {
            try {
                redisTemplate.execute(
                        COMPARE_AND_DELETE_SCRIPT, List.of(verificationKey), verificationValue);
            } catch (RuntimeException cleanupException) {
                e.addSuppressed(cleanupException);
            }
            throw e;
        }

        return new SignupEmailCodeResponse(PASSWORD_RESET_VERIFICATION_TTL_SECONDS);
    }

    @Override
    public PasswordResetVerifyResponse verifyPasswordResetCode(PasswordResetVerifyRequest request) {
        Map<String, Object> member = memberMapper.findActiveByEmail(request.getEmail());
        if (!isActiveLocalMember(member)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "인증번호가 만료되었거나 발급되지 않았습니다.");
        }

        String resetToken = UUID.randomUUID().toString();
        Long result = redisTemplate.execute(
                VERIFY_PASSWORD_RESET_CODE_SCRIPT,
                List.of(
                        passwordResetVerificationKey(request.getEmail()),
                        passwordResetTokenKey(resetToken)),
                request.getVerificationCode(),
                String.valueOf(PASSWORD_RESET_MAX_VERIFICATION_ATTEMPTS),
                String.valueOf(member.get("member_id")),
                String.valueOf(PASSWORD_RESET_TOKEN_TTL_SECONDS));

        if (result == null || result == 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "인증번호가 만료되었거나 발급되지 않았습니다.");
        }
        if (result == -1L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다.");
        }
        if (result != 1L) {
            throw new IllegalStateException("비밀번호 재설정 토큰을 발급할 수 없습니다.");
        }
        return new PasswordResetVerifyResponse(resetToken);
    }

    @Override
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String requestId = UUID.randomUUID().toString();
        String claimPrefix = PASSWORD_RESET_TOKEN_CLAIM_PREFIX + requestId + VERIFICATION_VALUE_DELIMITER;
        String tokenKey = passwordResetTokenKey(request.getResetToken());
        String memberId = redisTemplate.execute(
                CLAIM_PASSWORD_RESET_TOKEN_SCRIPT,
                List.of(tokenKey),
                claimPrefix);
        if (memberId == null) {
            throw new BusinessException(INVALID_PASSWORD_RESET_TOKEN_MESSAGE);
        }
        String claimedValue = claimPrefix + memberId;
        registerPasswordResetTokenCompletion(tokenKey, claimedValue, memberId);

        Map<String, Object> member = memberMapper.findById(memberId);
        if (!isActiveLocalMember(member)) {
            throw new BusinessException(INVALID_PASSWORD_RESET_TOKEN_MESSAGE);
        }

        String storedPassword = (String) member.get("password");
        if (storedPassword != null && passwordEncoder.matches(request.getNewPassword(), storedPassword)) {
            throw new BusinessException("새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }

        String encodedPassword = passwordEncoder.encode(request.getNewPassword());
        registerPasswordResetCredentialCleanup(memberId);
        if (memberMapper.updatePassword(memberId, encodedPassword) != 1) {
            throw BusinessException.conflict("비밀번호를 변경할 수 없는 회원 상태입니다.");
        }
    }

    @Override
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String completedKey = verificationCompletedKey(request.getEmail());
        String completedValue = redisTemplate.opsForValue().get(completedKey);
        validateCompletedVerification(completedValue, request.getVerificationCode());

        // DB 작업 전에 완료 키를 지우면 rollback 시 인증 결과도 잃으므로, commit 후 삭제할 전체 값을 보관한다.
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        if (memberMapper.existsActiveByEmail(request.getEmail())) {
            throw BusinessException.conflict("이미 가입된 이메일입니다.");
        }

        String normalizedPhone = PhoneNumberUtil.normalize(request.getPhone());
        if (normalizedPhone != null && !normalizedPhone.isEmpty()
                && memberMapper.existsActiveByPhone(normalizedPhone)) {
            throw BusinessException.conflict("이미 사용 중인 전화번호입니다.");
        }

        // 신규 가입과 탈퇴 회원 복구의 DB 변경을 지갑·알림 설정과 함께 하나의 트랜잭션으로 처리한다.
        Map<String, Object> inactiveMember = memberMapper.findLatestInactiveByEmailForUpdate(request.getEmail());
        SignupResponse response;
        if (inactiveMember != null) {
            response = restoreMember(request, encodedPassword, inactiveMember);
        } else {
            // 복구 잠금을 기다린 동안 다른 요청이 활성화했을 수 있으므로 삽입 전 한 번 더 확인한다.
            if (memberMapper.existsActiveByEmail(request.getEmail())) {
                throw BusinessException.conflict("이미 가입된 이메일입니다.");
            }
            response = createMember(request, encodedPassword);
        }

        registerCompletedKeyCleanup(completedKey, completedValue);
        return response;
    }

    @Override
    public TokenResponse login(LoginRequest request) {
        Map<String, Object> member = memberMapper.findActiveByEmail(request.getEmail());
        if (member == null) {
            throw BusinessException.unauthorized("이메일 또는 비밀번호가 잘못되었습니다.");
        }

        String memberId = String.valueOf(member.get("member_id"));
        String storedPassword = (String) member.get("password");
        if (storedPassword == null || !passwordEncoder.matches(request.getPassword(), storedPassword)) {
            throw BusinessException.unauthorized("이메일 또는 비밀번호가 잘못되었습니다.");
        }

        if (!"Y".equals(member.get("email_verified"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다.");
        }

        String role = (String) member.get("role");
        return generateTokens(memberId, role);
    }

    @Override
    public KakaoOAuthResponse kakaoLogin(String code) {
        String kakaoAccessToken = kakaoAuthClient.getAccessToken(code);
        KakaoUserInfo userInfo = kakaoAuthClient.getUserInfo(kakaoAccessToken);

        Map<String, Object> existingMember =
                memberMapper.findActiveKakaoByProviderId(userInfo.getProviderId());
        if (existingMember != null) {
            String memberId = String.valueOf(existingMember.get("member_id"));
            String role = (String) existingMember.get("role");
            return KakaoOAuthResponse.loginComplete(generateTokens(memberId, role));
        }

        Map<String, Object> activeEmailMember = memberMapper.findActiveByEmail(userInfo.getEmail());
        if (activeEmailMember != null && "LOCAL".equals(activeEmailMember.get("provider"))) {
            throw BusinessException.conflict("이미 가입된 이메일입니다.");
        }
        if (memberMapper.existsInactiveKakaoByProviderId(userInfo.getProviderId())) {
            throw BusinessException.unauthorized("카카오 로그인에 실패했습니다.");
        }

        String registrationToken = kakaoRegistrationStore.create(new KakaoRegistrationSession(
                userInfo.getProviderId(), userInfo.getEmail(), userInfo.getName()));
        return KakaoOAuthResponse.additionalInfoRequired(registrationToken);
    }

    @Override
    public TokenResponse refresh(String refreshToken) {
        if (!jwtUtil.isTokenValid(refreshToken)) {
            throw BusinessException.unauthorized("유효하지 않은 리프레시 토큰입니다.");
        }

        Claims claims = jwtUtil.parseClaims(refreshToken);
        if (!jwtUtil.isRefreshToken(claims)) {
            throw BusinessException.unauthorized("유효하지 않은 리프레시 토큰입니다.");
        }
        String memberId = claims.getSubject();
        Map<String, Object> member = memberMapper.findAuthStateById(memberId);
        if (!canUseToken(member, claims.getIssuedAt())) {
            throw BusinessException.unauthorized("리프레시 토큰이 만료되었습니다.");
        }
        String role = (String) member.get("role");
        String accessToken = jwtUtil.generateAccessToken(memberId, role);
        String newRefreshToken = jwtUtil.generateRefreshToken(memberId);
        if (!authCredentialStore.rotateRefreshAtomically(
                memberId, refreshToken, newRefreshToken)) {
            throw BusinessException.unauthorized("리프레시 토큰이 만료되었습니다.");
        }
        return tokenResponse(accessToken, newRefreshToken);
    }

    @Override
    public void logout(String memberId) {
        redisTemplate.delete("refresh:" + memberId);
        log.info("로그아웃이 완료되었습니다.");
    }

    private void validateCompletedVerification(String completedValue, String requestedCode) {
        if (completedValue == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "이메일 인증이 완료되지 않았거나 만료되었습니다.");
        }

        String[] parts = completedValue.split("\\|", -1);
        if (parts.length != 2 || !isUuid(parts[0]) || !parts[1].matches("\\d{6}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "이메일 인증이 완료되지 않았거나 만료되었습니다.");
        }
        if (!parts[1].equals(requestedCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다.");
        }
    }

    private SignupResponse createMember(SignupRequest request, String encodedPassword) {
        Map<String, Object> member = memberValues(request, encodedPassword);
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            // 사전 조회 후에도 동시 삽입이 가능하므로 DB unique 충돌을 일관된 409로 변환하고 전체 DB 작업을 rollback한다.
            throw BusinessException.conflict("이미 가입된 이메일입니다.");
        }

        Long memberId = generatedMemberId(member);
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("memberId", memberId);
        wallet.put("walletType", "MAIN");
        wallet.put("balance", 0);
        walletMapper.insert(wallet);

        notificationSettingMapper.insert(notificationSettingValues(memberId, request.isMarketing()));
        return new SignupResponse(memberId, request.getEmail(), request.getName());
    }

    private SignupResponse restoreMember(
            SignupRequest request, String encodedPassword, Map<String, Object> inactiveMember) {
        String provider = String.valueOf(value(inactiveMember, "provider", "provider"));
        if (!"LOCAL".equals(provider)) {
            throw BusinessException.conflict("탈퇴한 카카오 계정은 LOCAL 회원으로 복구할 수 없습니다.");
        }

        // DB에 기록된 탈퇴 시각과 같은 DB 시계를 사용해 경계 오차를 막고, 정확히 30일인 시점까지 복구를 허용한다.
        if (!booleanValue(inactiveMember, "recoverable_within_30_days", "recoverableWithin30Days")) {
            // 30일을 초과한 회원은 이번 회원가입에서 삭제하거나 신규 회원으로 생성하지 않는다.
            throw BusinessException.conflict("탈퇴 후 30일이 지난 회원은 현재 복구할 수 없습니다.");
        }

        Long memberId = numberValue(inactiveMember, "member_id", "memberId").longValue();
        Map<String, Object> restored = memberValues(request, encodedPassword);
        restored.put("memberId", memberId);
        try {
            if (memberMapper.restoreLocalMember(restored) != 1) {
                throw BusinessException.conflict("이미 활성화된 회원입니다.");
            }
        } catch (DuplicateKeyException e) {
            // 잠금 대기 중 다른 계정이 활성화된 경우에도 DB unique 충돌을 409로 일관되게 응답한다.
            throw BusinessException.conflict("이미 가입된 이메일입니다.");
        }

        // 레거시 회원의 누락 행은 기본값으로 보완하되, 기존 행이 있으면 사용자 선택을 보존하고 마케팅 동의만 최신 요청으로 반영한다.
        // 조회 후 update/insert로 분기하지 않고 upsert 하나로 처리해 동시 요청 사이의 간격을 제거한다.
        notificationSettingMapper.upsertForRecovery(memberId, request.isMarketing());
        return new SignupResponse(memberId, request.getEmail(), request.getName());
    }

    private Map<String, Object> memberValues(SignupRequest request, String encodedPassword) {
        Map<String, Object> member = new HashMap<>();
        member.put("email", request.getEmail());
        member.put("password", encodedPassword);
        member.put("name", request.getName());
        member.put("phone", PhoneNumberUtil.normalize(request.getPhone()));
        member.put("provider", "LOCAL");
        member.put("providerId", null);
        member.put("emailVerified", "Y");
        member.put("role", "USER");
        member.put("profileImg", null);
        member.put("zipCode", request.getZipCode());
        member.put("address", request.getAddress());
        member.put("addressDetail", request.getAddressDetail());
        return member;
    }

    private Map<String, Object> notificationSettingValues(Long memberId, boolean marketingEnabled) {
        Map<String, Object> setting = new HashMap<>();
        setting.put("memberId", memberId);
        setting.put("paymentEnabled", true);
        setting.put("recurringPaymentEnabled", true);
        setting.put("familyShareEnabled", true);
        setting.put("communityEnabled", true);
        setting.put("marketingEnabled", marketingEnabled);
        return setting;
    }

    private void registerCompletedKeyCleanup(String completedKey, String completedValue) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 동기화가 비활성 상태여서 이메일 인증 완료 키 정리를 등록하지 못했습니다.");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // DB commit 후에만 소비하고, Lua 비교 삭제로 그사이 새로 완료된 인증 값은 보호한다.
                    redisTemplate.execute(
                            COMPARE_AND_DELETE_SCRIPT, List.of(completedKey), completedValue);
                } catch (RuntimeException e) {
                    // DB는 이미 commit되었으므로 성공 응답을 바꾸지 않고, 삭제되지 않은 키는 300초 TTL 만료에 맡긴다.
                    log.warn("DB 커밋 후 이메일 인증 완료 키 정리에 실패했습니다. TTL 만료를 기다립니다.", e);
                }
            }
        });
    }

    private Long generatedMemberId(Map<String, Object> member) {
        Object memberId = member.get("memberId");
        if (!(memberId instanceof Number)) {
            throw new IllegalStateException("생성된 회원 ID를 확인할 수 없습니다.");
        }
        return ((Number) memberId).longValue();
    }

    private boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Object value(Map<String, Object> values, String snakeCaseKey, String camelCaseKey) {
        return values.containsKey(snakeCaseKey) ? values.get(snakeCaseKey) : values.get(camelCaseKey);
    }

    private Number numberValue(Map<String, Object> values, String snakeCaseKey, String camelCaseKey) {
        Object result = value(values, snakeCaseKey, camelCaseKey);
        if (!(result instanceof Number)) {
            throw new IllegalStateException("회원 ID를 확인할 수 없습니다.");
        }
        return (Number) result;
    }

    private boolean booleanValue(Map<String, Object> values, String snakeCaseKey, String camelCaseKey) {
        Object result = value(values, snakeCaseKey, camelCaseKey);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        return result instanceof Number && ((Number) result).intValue() == 1;
    }

    private boolean isActive(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value instanceof Number && ((Number) value).intValue() == 1;
    }

    private TokenResponse generateTokens(String memberId, String role) {
        String accessToken = jwtUtil.generateAccessToken(memberId, role);
        String refreshToken = jwtUtil.generateRefreshToken(memberId);
        authCredentialStore.storeRefresh(memberId, refreshToken);

        return tokenResponse(accessToken, refreshToken);
    }

    private boolean canUseToken(Map<String, Object> member, Date issuedAt) {
        if (member == null || !isActive(member.get("is_active")) || issuedAt == null) {
            return false;
        }
        Object withdrawnAtEpoch = member.get("withdrawn_at_epoch");
        if (withdrawnAtEpoch == null) {
            return true;
        }
        // 마지막 탈퇴 시각과 같거나 이전에 발급된 Refresh Token은 복구 후에도 거절한다.
        return issuedAt.getTime() / 1000L > ((Number) withdrawnAtEpoch).longValue();
    }

    private TokenResponse tokenResponse(String accessToken, String refreshToken) {
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private String generateVerificationCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private String verificationCodeKey(String email) {
        return SIGNUP_VERIFICATION_CODE_KEY_PREFIX + email;
    }

    private String verificationCompletedKey(String email) {
        return SIGNUP_VERIFICATION_COMPLETED_KEY_PREFIX + email;
    }

    private boolean isActiveLocalMember(Map<String, Object> member) {
        if (member == null || !"LOCAL".equals(member.get("provider"))) {
            return false;
        }
        Object active = member.get("is_active");
        return active instanceof Boolean
                ? (Boolean) active
                : active instanceof Number && ((Number) active).intValue() == 1;
    }

    private void registerPasswordResetCredentialCleanup(String memberId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    authCredentialStore.deleteRefresh(memberId);
                } catch (RuntimeException e) {
                    log.warn("비밀번호 재설정 후 Refresh Token 삭제에 실패했습니다. TTL 만료를 기다립니다.", e);
                }
            }
        });
    }

    private void registerPasswordResetTokenCompletion(
            String tokenKey, String claimedValue, String memberId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisTemplate.execute(
                            COMPARE_AND_DELETE_SCRIPT, List.of(tokenKey), claimedValue);
                } catch (RuntimeException e) {
                    log.warn("비밀번호 재설정 토큰 사용 완료 처리에 실패했습니다. TTL 만료를 기다립니다.", e);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    redisTemplate.execute(
                            COMPARE_AND_RESTORE_SCRIPT,
                            List.of(tokenKey),
                            claimedValue,
                            memberId);
                } catch (RuntimeException e) {
                    log.warn("비밀번호 재설정 토큰 복원에 실패했습니다. TTL 만료를 기다립니다.", e);
                }
            }
        });
    }

    private String passwordResetVerificationKey(String email) {
        return PASSWORD_RESET_VERIFICATION_KEY_PREFIX + email;
    }

    private String passwordResetTokenKey(String resetToken) {
        return PASSWORD_RESET_TOKEN_KEY_PREFIX + resetToken;
    }
}
