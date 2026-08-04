package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.dto.VerifyRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.smtp.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final MemberMapper memberMapper;
    private final WalletMapper walletMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, String> redisTemplate;
    private final EmailService emailService;
    private final KakaoAuthClient kakaoAuthClient;

    @Override
    @Transactional
    public void signup(SignupRequest request) {
        if (memberMapper.findByEmail(request.getEmail()) != null) {
            throw BusinessException.conflict("이미 가입된 이메일입니다.");
        }

        Map<String, Object> member = new HashMap<>();
        member.put("memberId", UUID.randomUUID().toString());
        member.put("email", request.getEmail());
        member.put("password", passwordEncoder.encode(request.getPassword()));
        member.put("nickname", request.getNickname());
        member.put("name", request.getName());
        member.put("phone", request.getPhone());
        member.put("provider", "LOCAL");
        member.put("providerId", null);
        member.put("emailVerified", "N");
        member.put("role", "USER");
        member.put("profileImg", null);
        member.put("region", null);
        member.put("incomeLevel", null);
        memberMapper.insert(member);

        // 인증코드 생성 및 Redis 저장 (5분 TTL)
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        redisTemplate.opsForValue().set("verify:" + request.getEmail(), code, 5, TimeUnit.MINUTES);

        // 인증 이메일 발송
        emailService.sendVerificationEmail(request.getEmail(), code);
        log.info("회원가입 접수 완료 - email: {}", request.getEmail());
    }

    @Override
    @Transactional
    public TokenResponse verifyEmail(VerifyRequest request) {
        String storedCode = redisTemplate.opsForValue().get("verify:" + request.getEmail());
        if (storedCode == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증코드가 만료되었습니다.");
        }
        if (!storedCode.equals(request.getCode())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증코드가 일치하지 않습니다.");
        }

        Map<String, Object> member = memberMapper.findByEmail(request.getEmail());
        if (member == null) {
            throw BusinessException.notFound("회원 정보를 찾을 수 없습니다.");
        }

        String memberId = String.valueOf(member.get("member_id")); // V3에서 member_id가 BIGINT로 전환되어 Long이 반환됨

        // 이메일 인증 완료 처리
        Map<String, Object> update = new HashMap<>();
        update.put("memberId", memberId);
        update.put("nickname", member.get("nickname"));
        update.put("phone", member.get("phone"));
        update.put("profileImg", member.get("profile_img"));
        update.put("region", member.get("region"));
        update.put("incomeLevel", member.get("income_level"));
        memberMapper.update(update);

        // 지갑 자동 생성
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("walletId", UUID.randomUUID().toString());
        wallet.put("memberId", memberId);
        wallet.put("totalBalance", 0);
        walletMapper.insert(wallet);

        redisTemplate.delete("verify:" + request.getEmail());

        return generateTokens(memberId, "USER");
    }

    @Override
    public TokenResponse login(LoginRequest request) {
        Map<String, Object> member = memberMapper.findByEmail(request.getEmail());
        if (member == null) {
            throw BusinessException.unauthorized("이메일 또는 비밀번호가 잘못되었습니다.");
        }

        String storedPassword = (String) member.get("password");
        if (storedPassword == null || !passwordEncoder.matches(request.getPassword(), storedPassword)) {
            throw BusinessException.unauthorized("이메일 또는 비밀번호가 잘못되었습니다.");
        }

        if (!"Y".equals(member.get("email_verified"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다.");
        }

        String memberId = String.valueOf(member.get("member_id")); // V3에서 member_id가 BIGINT로 전환되어 Long이 반환됨
        String role = (String) member.get("role");
        return generateTokens(memberId, role);
    }

    @Override
    @Transactional
    public TokenResponse kakaoLogin(String code) {
        Map<String, Object> tokenInfo = kakaoAuthClient.getAccessToken(code);
        String kakaoAccessToken = (String) tokenInfo.get("access_token");
        Map<String, Object> profile = kakaoAuthClient.getUserProfile(kakaoAccessToken);

        String kakaoId = String.valueOf(profile.get("id"));
        Map<String, Object> kakaoAccount = (Map<String, Object>) profile.get("kakao_account");
        String email = kakaoAccount != null ? (String) kakaoAccount.get("email") : null;
        Map<String, Object> profileInfo = kakaoAccount != null ? (Map<String, Object>) kakaoAccount.get("profile") : null;
        String nickname = profileInfo != null ? (String) profileInfo.get("nickname") : "카카오유저";

        Map<String, Object> existingMember = email != null ? memberMapper.findByEmail(email) : null;

        String memberId;
        if (existingMember == null) {
            memberId = UUID.randomUUID().toString();
            Map<String, Object> member = new HashMap<>();
            member.put("memberId", memberId);
            member.put("email", email != null ? email : kakaoId + "@kakao.user");
            member.put("password", null);
            member.put("nickname", nickname);
            member.put("name", nickname);
            member.put("phone", null);
            member.put("provider", "KAKAO");
            member.put("providerId", kakaoId);
            member.put("emailVerified", "Y");
            member.put("role", "USER");
            member.put("profileImg", null);
            member.put("region", null);
            member.put("incomeLevel", null);
            memberMapper.insert(member);

            Map<String, Object> wallet = new HashMap<>();
            wallet.put("walletId", UUID.randomUUID().toString());
            wallet.put("memberId", memberId);
            wallet.put("totalBalance", 0);
            walletMapper.insert(wallet);
        } else {
            memberId = String.valueOf(existingMember.get("member_id"));
        }

        return generateTokens(memberId, "USER");
    }

    @Override
    public TokenResponse refresh(String refreshToken) {
        if (!jwtUtil.isTokenValid(refreshToken)) {
            throw BusinessException.unauthorized("유효하지 않은 리프레시 토큰입니다.");
        }

        String memberId = jwtUtil.getSubject(refreshToken);
        String storedToken = redisTemplate.opsForValue().get("refresh:" + memberId);
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw BusinessException.unauthorized("리프레시 토큰이 만료되었습니다.");
        }

        Map<String, Object> member = memberMapper.findById(memberId);
        String role = member != null ? (String) member.get("role") : "USER";

        redisTemplate.delete("refresh:" + memberId);
        return generateTokens(memberId, role);
    }

    @Override
    public void logout(String memberId) {
        redisTemplate.delete("refresh:" + memberId);
        log.info("로그아웃 완료 - memberId: {}", memberId);
    }

    @Override
    @Transactional
    public void withdraw(String memberId) {
        // soft delete — 실제 삭제 대신 비활성화 등 처리
        log.info("회원 탈퇴 처리 - memberId: {}", memberId);
        redisTemplate.delete("refresh:" + memberId);
    }

    private TokenResponse generateTokens(String memberId, String role) {
        String accessToken = jwtUtil.generateAccessToken(memberId, role);
        String refreshToken = jwtUtil.generateRefreshToken(memberId);

        // Redis에 Refresh Token 저장 (RTR)
        redisTemplate.opsForValue().set(
                "refresh:" + memberId, refreshToken,
                jwtUtil.getRefreshTokenExpiry(), TimeUnit.MILLISECONDS
        );

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
}
