package com.aewol.domain.member.controller;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.member.dto.MemberWithdrawRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.member.service.MemberService;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserWithdrawalSecurityTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MemberService memberService;
    private MemberMapper memberMapper;
    private JwtUtil jwtUtil;
    private AuthCredentialStore authCredentialStore;

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

        memberService = context.getBean(MemberService.class);
        memberMapper = context.getBean(MemberMapper.class);
        jwtUtil = context.getBean(JwtUtil.class);
        authCredentialStore = context.getBean(AuthCredentialStore.class);
        reset(memberService, memberMapper, jwtUtil, authCredentialStore);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void unauthenticatedDeleteIsBlockedByActualSecurityChain() throws Exception {
        mockMvc.perform(delete("/api/users/me")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endpointUsesJwtPrincipalAndIgnoresBodyMemberId() throws Exception {
        stubActiveAccessToken(true);
        ArgumentCaptor<MemberWithdrawRequest> requestCaptor =
                ArgumentCaptor.forClass(MemberWithdrawRequest.class);

        MvcResult result = mockMvc.perform(delete("/api/users/me")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"current-password\",\"memberId\":\"other-member\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, json.get("status").asInt());
        assertEquals("회원탈퇴가 완료되었습니다.", json.get("message").asText());
        assertEquals(true, json.has("result"));
        assertEquals(true, json.get("result").isNull());

        verify(memberService).withdraw(org.mockito.ArgumentMatchers.eq("member-1"), requestCaptor.capture());
        assertEquals("current-password", requestCaptor.getValue().getCurrentPassword());
    }

    @Test
    void sameAccessTokenIsRejectedAfterMemberBecomesInactive() throws Exception {
        stubActiveAccessToken(true, false);

        mockMvc.perform(authenticatedDelete()).andExpect(status().isOk());
        mockMvc.perform(authenticatedDelete()).andExpect(status().isUnauthorized());

        verify(memberService, times(1)).withdraw(
                org.mockito.ArgumentMatchers.eq("member-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requestThatPassedFilterButLosesConditionalUpdateRaceReturnsConflict() throws Exception {
        stubActiveAccessToken(true, true);
        doNothing().doThrow(BusinessException.conflict("이미 탈퇴한 회원입니다."))
                .when(memberService).withdraw(
                        org.mockito.ArgumentMatchers.eq("member-1"), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(authenticatedDelete()).andExpect(status().isOk());
        MvcResult result = mockMvc.perform(authenticatedDelete())
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(409, json.get("status").asInt());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedDelete() {
        return delete("/api/users/me")
                .header("Authorization", "Bearer access-token")
                .contentType("application/json")
                .content("{}");
    }

    private void stubActiveAccessToken(boolean... activeResults) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("member-1");
        when(claims.get("role", String.class)).thenReturn("USER");
        when(claims.getIssuedAt()).thenReturn(new Date(2_000_000L));
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(true);
        if (activeResults.length == 1) {
            when(memberMapper.findAuthStateById("member-1")).thenReturn(authState(activeResults[0]));
        } else {
            Map<String, Object> first = authState(activeResults[0]);
            Map<String, Object>[] remaining = new Map[activeResults.length - 1];
            for (int i = 1; i < activeResults.length; i++) {
                remaining[i - 1] = authState(activeResults[i]);
            }
            when(memberMapper.findAuthStateById("member-1")).thenReturn(first, remaining);
        }
    }

    private Map<String, Object> authState(boolean active) {
        Map<String, Object> state = new HashMap<>();
        state.put("is_active", active ? 1 : 0);
        return state;
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        MemberService memberService() {
            return mock(MemberService.class);
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
        AuthCredentialStore authCredentialStore() {
            return mock(AuthCredentialStore.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, MemberMapper memberMapper) {
            return new JwtAuthenticationFilter(jwtUtil, memberMapper);
        }

        @Bean
        UserController userController(MemberService memberService) {
            return new UserController(memberService);
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
