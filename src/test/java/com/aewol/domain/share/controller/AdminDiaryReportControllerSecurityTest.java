package com.aewol.domain.share.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.util.JwtUtil;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.share.service.CareDiaryService;
import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class AdminDiaryReportControllerSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private CareDiaryService careDiaryService;
    private MemberMapper memberMapper;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "jwt.secret=test-secret-key-with-at-least-32-bytes",
                "jwt.access-token-expiry=1800000",
                "jwt.refresh-token-expiry=604800000");
        context.register(TestConfig.class);
        context.refresh();
        careDiaryService = context.getBean(CareDiaryService.class);
        memberMapper = context.getBean(MemberMapper.class);
        jwtUtil = context.getBean(JwtUtil.class);
        reset(careDiaryService, memberMapper, jwtUtil);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void listIsBlockedForUserRole() throws Exception {
        stubAccessToken("USER");
        mockMvc.perform(get("/api/admin/diary-reports")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isForbidden());
        verify(careDiaryService, never()).getAdminReports(any(), anyInt(), anyInt());
    }

    @Test
    void listIsAllowedForAdminRole() throws Exception {
        stubAccessToken("ADMIN");
        mockMvc.perform(get("/api/admin/diary-reports")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());
        verify(careDiaryService).getAdminReports(null, 0, 20);
    }

    @Test
    void resolutionIsBlockedForUserRole() throws Exception {
        stubAccessToken("USER");
        mockMvc.perform(put("/api/admin/diary-reports/1/resolution")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"resolution\":\"KEEP_HIDDEN\"}"))
                .andExpect(status().isForbidden());
        verify(careDiaryService, never()).resolveAdminReport(any(), any(), any());
    }

    @Test
    void resolutionIsAllowedForAdminRole() throws Exception {
        stubAccessToken("ADMIN");
        mockMvc.perform(put("/api/admin/diary-reports/1/resolution")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"resolution\":\"KEEP_HIDDEN\"}"))
                .andExpect(status().isOk());
        verify(careDiaryService).resolveAdminReport(eq("member-1"), eq("1"), any());
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

    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean CareDiaryService careDiaryService() { return mock(CareDiaryService.class); }
        @Bean MemberMapper memberMapper() { return mock(MemberMapper.class); }
        @Bean JwtUtil jwtUtil() { return mock(JwtUtil.class); }
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, MemberMapper memberMapper) {
            return new JwtAuthenticationFilter(jwtUtil, MemberAuthStateCache.withoutCache(memberMapper));
        }
        @Bean AdminDiaryReportController adminDiaryReportController(CareDiaryService service) {
            return new AdminDiaryReportController(service);
        }
        @Bean GlobalExceptionHandler globalExceptionHandler() { return new GlobalExceptionHandler(); }
    }
}
