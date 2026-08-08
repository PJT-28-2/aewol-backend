package com.aewol.common.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtUtilIssuedAtTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-with-at-least-32-bytes");
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiry", 1_800_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenExpiry", 604_800_000L);
        jwtUtil.init();
    }

    @Test
    void accessTokenUsesStandardIssuedAtWithoutAuthEpoch() {
        Claims claims = jwtUtil.parseClaims(jwtUtil.generateAccessToken("member-1", "USER"));

        assertEquals("member-1", claims.getSubject());
        assertEquals("USER", claims.get("role", String.class));
        assertEquals("ACCESS", claims.get("tokenType", String.class));
        assertTrue(jwtUtil.isAccessToken(claims));
        assertFalse(jwtUtil.isRefreshToken(claims));
        assertNotNull(claims.getIssuedAt());
        assertNull(claims.get("authEpoch"));
    }

    @Test
    void refreshTokenAlsoHasIssuedAt() {
        Claims claims = jwtUtil.parseClaims(jwtUtil.generateRefreshToken("member-1"));

        assertEquals("member-1", claims.getSubject());
        assertEquals("REFRESH", claims.get("tokenType", String.class));
        assertTrue(jwtUtil.isRefreshToken(claims));
        assertFalse(jwtUtil.isAccessToken(claims));
        assertNotNull(claims.getIssuedAt());
    }

    @Test
    void missingEmptyAndUnknownTokenTypesFailClosed() {
        Claims claims = mock(Claims.class);
        when(claims.get("tokenType", String.class)).thenReturn(null, "", "UNKNOWN");

        assertFalse(jwtUtil.isAccessToken(claims));
        assertFalse(jwtUtil.isAccessToken(claims));
        assertFalse(jwtUtil.isAccessToken(claims));

        when(claims.get("tokenType", String.class)).thenReturn(null, "", "UNKNOWN");
        assertFalse(jwtUtil.isRefreshToken(claims));
        assertFalse(jwtUtil.isRefreshToken(claims));
        assertFalse(jwtUtil.isRefreshToken(claims));
    }
}
