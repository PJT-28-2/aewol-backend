package com.aewol.domain.auth.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.util.JwtUtil;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.auth.service.AccountFindService;
import com.aewol.domain.auth.service.AuthService;
import com.aewol.domain.auth.support.KakaoRegistrationCookie;
import com.aewol.domain.member.mapper.MemberMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerLogoutSecurityTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthService authService;
    private JwtUtil jwtUtil;
    private MemberMapper memberMapper;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                "jwt.secret=test-secret-key-with-at-least-32-bytes",
                "jwt.access-token-expiry=1800000",
                "jwt.refresh-token-expiry=604800000");
        context.register(TestConfig.class);
        context.refresh();

        authService = context.getBean(AuthService.class);
        jwtUtil = context.getBean(JwtUtil.class);
        memberMapper = context.getBean(MemberMapper.class);
        reset(authService, jwtUtil, memberMapper);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void missingAuthorizationIsRejected() throws Exception {
        assertUnauthorized(post("/api/auth/logout"));
        verifyNoInteractions(jwtUtil, memberMapper);
    }

    @Test
    void validAccessTokenLogsOutAuthenticatedMember() throws Exception {
        Claims claims = accessClaims();
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(true);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(activeMember());

        mockMvc.perform(logoutWith("access-token"))
                .andExpect(status().isOk());

        verify(authService).logout("member-1");
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        when(jwtUtil.isTokenValid("expired-access-token")).thenReturn(false);

        assertUnauthorized(logoutWith("expired-access-token"));

        verifyNoInteractions(authService);
    }

    @Test
    void invalidAccessTokenIsRejected() throws Exception {
        when(jwtUtil.isTokenValid("invalid-access-token")).thenReturn(false);

        assertUnauthorized(logoutWith("invalid-access-token"));

        verifyNoInteractions(authService);
    }

    @Test
    void refreshTokenIsRejected() throws Exception {
        Claims claims = mock(Claims.class);
        when(jwtUtil.isTokenValid("refresh-token")).thenReturn(true);
        when(jwtUtil.parseClaims("refresh-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(false);

        assertUnauthorized(logoutWith("refresh-token"));

        verifyNoInteractions(authService, memberMapper);
    }

    private void assertUnauthorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(401, json.get("status").asInt());
        assertEquals("로그인이 필요합니다.", json.get("message").asText());
        verifyNoInteractions(authService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder logoutWith(String token) {
        return post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private Claims accessClaims() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("member-1");
        when(claims.get("role", String.class)).thenReturn("USER");
        when(claims.getIssuedAt()).thenReturn(new Date(2_000_000L));
        return claims;
    }

    private Map<String, Object> activeMember() {
        Map<String, Object> member = new HashMap<>();
        member.put("is_active", 1);
        member.put("role", "USER");
        return member;
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        AccountFindService accountFindService() {
            return mock(AccountFindService.class);
        }

        @Bean
        MemberMapper memberMapper() {
            return mock(MemberMapper.class);
        }

        @Bean
        JwtUtil jwtUtil() {
            return mock(JwtUtil.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, MemberMapper memberMapper) {
            return new JwtAuthenticationFilter(jwtUtil, MemberAuthStateCache.withoutCache(memberMapper));
        }

        @Bean
        AuthController authController(AuthService authService, AccountFindService accountFindService) {
            return new AuthController(authService, accountFindService, new KakaoRegistrationCookie(false));
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
