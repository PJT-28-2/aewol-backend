package com.aewol.domain.auth.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.exception.BusinessException;
import com.aewol.domain.auth.dto.PasswordResetVerifyResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.service.AuthService;
import com.aewol.domain.auth.service.AccountFindService;
import com.aewol.domain.auth.support.KakaoRegistrationCookie;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerPasswordResetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService, mock(AccountFindService.class), new KakaoRegistrationCookie(false)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void resetRequestReturnsSpecifiedSuccessContract() throws Exception {
        when(authService.sendPasswordResetVerificationCode(any()))
                .thenReturn(new SignupEmailCodeResponse(300L));

        JsonNode json = json(mockMvc.perform(post("/api/auth/password/reset-request")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals(200, json.get("status").asInt());
        assertEquals("비밀번호 재설정 인증번호가 발송되었습니다.", json.get("message").asText());
        assertEquals(300L, json.get("result").get("expiresInSeconds").asLong());
        verify(authService).sendPasswordResetVerificationCode(any());
    }

    @Test
    void resetRequestRateLimitReturnsExistingErrorContract() throws Exception {
        when(authService.sendPasswordResetVerificationCode(any()))
                .thenThrow(new BusinessException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "비밀번호 재설정 요청이 너무 많아요. 30분 후 다시 시도해주세요"));

        JsonNode json = json(mockMvc.perform(post("/api/auth/password/reset-request")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn());

        assertEquals(429, json.get("status").asInt());
        assertEquals("비밀번호 재설정 요청이 너무 많아요. 30분 후 다시 시도해주세요",
                json.get("message").asText());
        assertTrue(json.has("result"));
        assertTrue(json.get("result").isNull());
    }

    @Test
    void resetRequestMailFailureReturnsStandardServiceUnavailableResponse() throws Exception {
        when(authService.sendPasswordResetVerificationCode(any()))
                .thenThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "인증 이메일을 발송할 수 없습니다. 잠시 후 다시 시도해주세요."));

        JsonNode json = json(mockMvc.perform(post("/api/auth/password/reset-request")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn());

        assertEquals(503, json.get("status").asInt());
        assertEquals("인증 이메일을 발송할 수 없습니다. 잠시 후 다시 시도해주세요.",
                json.get("message").asText());
        assertTrue(json.get("result").isNull());
    }

    @Test
    void resetVerifyReturnsResetTokenContract() throws Exception {
        when(authService.verifyPasswordResetCode(any()))
                .thenReturn(new PasswordResetVerifyResponse("opaque-token"));

        JsonNode json = json(mockMvc.perform(post("/api/auth/password/reset-verify")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals("인증번호가 확인되었습니다.", json.get("message").asText());
        assertEquals("opaque-token", json.get("result").get("resetToken").asText());
    }

    @Test
    void resetReturnsNullResultContract() throws Exception {
        JsonNode json = json(mockMvc.perform(post("/api/auth/password/reset")
                        .contentType("application/json")
                        .content("{\"resetToken\":\"opaque-token\",\"newPassword\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals("비밀번호가 재설정되었습니다.", json.get("message").asText());
        assertTrue(json.has("result"));
        assertTrue(json.get("result").isNull());
    }

    @Test
    void emailAndOtpValidationRejectMalformedRequests() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset-request")
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/password/reset-verify")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\",\"verificationCode\":\"12345\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    void passwordValidationRejectsShortAndLongValues() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType("application/json")
                        .content("{\"resetToken\":\"token\",\"newPassword\":\"1234567\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType("application/json")
                        .content("{\"resetToken\":\"token\",\"newPassword\":\"123456789012345678901\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
