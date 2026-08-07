package com.aewol.common.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilAuthEpochTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(
                jwtUtil, "secret", "test-secret-key-with-at-least-32-bytes");
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiry", 1_800_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenExpiry", 604_800_000L);
        jwtUtil.init();
    }

    @Test
    void accessTokenCarriesOpaqueEpochWhenSnapshotExists() {
        String token = jwtUtil.generateAccessToken("member-1", "USER", "epoch-2");

        Claims claims = jwtUtil.parseClaims(token);
        assertEquals("member-1", claims.getSubject());
        assertEquals("USER", claims.get("role", String.class));
        assertEquals("epoch-2", claims.get("authEpoch", String.class));
    }

    @Test
    void accessTokenCannotBeIssuedWithoutEpoch() {
        assertThrows(IllegalArgumentException.class,
                () -> jwtUtil.generateAccessToken("member-1", "USER", null));
    }
}
