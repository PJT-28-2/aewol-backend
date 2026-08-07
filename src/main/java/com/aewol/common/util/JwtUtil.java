package com.aewol.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiry}")
    private long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(String memberId, String role, String authEpoch) {
        if (authEpoch == null || authEpoch.isBlank()) {
            throw new IllegalArgumentException("Access Token 발급에는 인증 세대가 필요합니다.");
        }
        var builder = Jwts.builder()
                .subject(memberId)
                .claim("role", role)
                .claim("authEpoch", authEpoch)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry));
        return builder.signWith(secretKey).compact();
    }

    public String generateRefreshToken(String memberId) {
        return Jwts.builder()
                .subject(memberId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiry))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getSubject(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("JWT invalid: {}", e.getMessage());
            return false;
        }
    }

    public long getRefreshTokenExpiry() {
        return refreshTokenExpiry;
    }

    public long getAccessTokenExpiry() {
        return accessTokenExpiry;
    }
}
