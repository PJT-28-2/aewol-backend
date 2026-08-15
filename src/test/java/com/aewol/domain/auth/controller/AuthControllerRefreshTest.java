package com.aewol.domain.auth.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.util.JwtUtil;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.dto.AccountFindSendCodeResponse;
import com.aewol.domain.auth.dto.AccountFindResultResponse;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.auth.service.AuthService;
import com.aewol.domain.auth.service.AccountFindService;
import com.aewol.domain.member.mapper.MemberMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerRefreshTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthService authService;
    private JwtUtil jwtUtil;
    private MemberMapper memberMapper;
    private AccountFindService accountFindService;

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
        accountFindService = context.getBean(AccountFindService.class);
        reset(authService, jwtUtil, memberMapper, accountFindService);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void bearerRefreshTokenIsStrippedAndReturnsSpecifiedResponse() throws Exception {
        TokenResponse tokenResponse = TokenResponse.builder()
                .accessToken("new-access-token")
                .refreshToken("new-refresh-token")
                .build();
        when(authService.refresh("refresh-token")).thenReturn(tokenResponse);

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .with(csrf())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer refresh-token"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, json.get("status").asInt());
        assertEquals("토큰이 재발급되었습니다.", json.get("message").asText());
        assertEquals("new-access-token", json.get("result").get("accessToken").asText());
        assertEquals("new-refresh-token", json.get("result").get("refreshToken").asText());
        verify(authService).refresh("refresh-token");
        verifyNoInteractions(jwtUtil, memberMapper);
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        when(authService.refresh("refresh-token")).thenReturn(TokenResponse.builder()
                .accessToken("access-token").refreshToken("rotated-token").build());

        for (String scheme : new String[]{"Bearer", "bearer", "BEARER", "BeArEr"}) {
            mockMvc.perform(post("/api/auth/refresh")
                            .with(csrf())
                            .header(HttpHeaders.AUTHORIZATION, scheme + " refresh-token"))
                    .andExpect(status().isOk());
        }
        verify(authService, org.mockito.Mockito.times(4)).refresh("refresh-token");
    }

    @Test
    void missingAuthorizationHeaderIsRejected() throws Exception {
        assertInvalidAuthorization(post("/api/auth/refresh").with(csrf()));
    }

    @Test
    void emptyAuthorizationHeaderIsRejected() throws Exception {
        assertInvalidAuthorization(post("/api/auth/refresh").with(csrf())
                .header(HttpHeaders.AUTHORIZATION, ""));
    }

    @Test
    void authorizationWithoutBearerPrefixIsRejected() throws Exception {
        assertInvalidAuthorization(post("/api/auth/refresh").with(csrf())
                .header(HttpHeaders.AUTHORIZATION, "refresh-token"));
    }

    @Test
    void wrongAuthorizationSchemeIsRejected() throws Exception {
        assertInvalidAuthorization(post("/api/auth/refresh").with(csrf())
                .header(HttpHeaders.AUTHORIZATION, "Basic refresh-token"));
    }

    @Test
    void emptyBearerValueIsRejected() throws Exception {
        assertInvalidAuthorization(post("/api/auth/refresh").with(csrf())
                .header(HttpHeaders.AUTHORIZATION, "Bearer "));
    }

    @Test
    void malformedBearerSpacingIsRejected() throws Exception {
        assertInvalidAuthorization(post("/api/auth/refresh").with(csrf())
                .header(HttpHeaders.AUTHORIZATION, "Bearer  refresh-token"));
    }

    @Test
    void legacyRefreshHeaderAloneIsRejected() throws Exception {
        assertInvalidAuthorization(post("/api/auth/refresh").with(csrf())
                .header("X-Refresh-Token", "refresh-token"));
    }

    @Test
    void accountFindEndpointsAllowAnonymousRequests() throws Exception {
        when(accountFindService.sendVerificationCode(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AccountFindSendCodeResponse("opaque-request-id", 300L));
        when(accountFindService.verifyCode(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AccountFindResultResponse("KAKAO", null));

        mockMvc.perform(post("/api/auth/account/find/send-code")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"홍길동\",\"phone\":\"01012345678\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/account/find/verify-code")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"requestId\":\"123e4567-e89b-12d3-a456-426614174000\","
                                + "\"verificationCode\":\"123456\"}"))
                .andExpect(status().isOk());
    }

    private void assertInvalidAuthorization(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(401, json.get("status").asInt());
        assertEquals("유효하지 않은 리프레시 토큰입니다.", json.get("message").asText());
        verifyNoInteractions(authService, jwtUtil, memberMapper);
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
        AccountFindService accountFindService() {
            return mock(AccountFindService.class);
        }

        @Bean
        AuthController authController(AuthService authService, AccountFindService accountFindService) {
            return new AuthController(authService, accountFindService);
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
