package com.aewol.domain.grouppurchase.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.util.JwtUtil;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.grouppurchase.service.GroupPurchaseService;
import com.aewol.domain.member.mapper.MemberMapper;
import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공동구매 참여(join)는 role=USER만 호출할 수 있어야 한다(SecurityConfig의
 * POST /api/group-purchase/{gpId}/join 경로 제한). 실제 시큐리티 필터 체인을 태워서 검증하며,
 * 서비스/매퍼는 목으로 대체한다(GroupPurchaseAdminOnlySecurityTest와 동일한 패턴).
 */
class GroupPurchaseJoinUserOnlySecurityTest {

    private static final String VALID_JOIN_REQUEST_BODY = "{"
            + "\"password\":\"123456\","
            + "\"recipientName\":\"김애월\","
            + "\"recipientPhone\":\"010-1234-5678\","
            + "\"zipCode\":\"16856\","
            + "\"address\":\"서울특별시 광진구 화양동\","
            + "\"addressDetail\":\"세종대점 컴포즈 302호\""
            + "}";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private GroupPurchaseService groupPurchaseService;
    private MemberMapper memberMapper;
    private JwtUtil jwtUtil;

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

        groupPurchaseService = context.getBean(GroupPurchaseService.class);
        memberMapper = context.getBean(MemberMapper.class);
        jwtUtil = context.getBean(JwtUtil.class);
        reset(groupPurchaseService, memberMapper, jwtUtil);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void joinIsBlockedForAdminRole() throws Exception {
        stubAccessToken("ADMIN");

        mockMvc.perform(post("/api/group-purchase/1/join")
                        .header("Authorization", "Bearer access-token")
                        .param("quantity", "1")
                        .contentType("application/json")
                        .content(VALID_JOIN_REQUEST_BODY))
                .andExpect(status().isForbidden());

        verify(groupPurchaseService, never()).join(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void joinIsAllowedForUserRole() throws Exception {
        stubAccessToken("USER");

        mockMvc.perform(post("/api/group-purchase/1/join")
                        .header("Authorization", "Bearer access-token")
                        .param("quantity", "1")
                        .contentType("application/json")
                        .content(VALID_JOIN_REQUEST_BODY))
                .andExpect(status().isOk());

        verify(groupPurchaseService).join(
                org.mockito.ArgumentMatchers.eq("member-1"), org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listIsStillOpenToAdminRole() throws Exception {
        stubAccessToken("ADMIN");

        mockMvc.perform(get("/api/group-purchase")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());
    }

    private void stubAccessToken(String role) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("member-1");
        when(claims.get("role", String.class)).thenReturn(role);
        when(claims.getIssuedAt()).thenReturn(new Date(2_000_000L));
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(true);

        Map<String, Object> authState = new HashMap<>();
        authState.put("is_active", 1);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(authState);
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        GroupPurchaseService groupPurchaseService() {
            return mock(GroupPurchaseService.class);
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
        GroupPurchaseController groupPurchaseController(GroupPurchaseService groupPurchaseService) {
            return new GroupPurchaseController(groupPurchaseService);
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
