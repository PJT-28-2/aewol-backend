package com.aewol.domain.auth.controller;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.response.ApiResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.service.AuthService;
import com.aewol.domain.auth.service.AccountFindService;
import com.aewol.domain.auth.support.KakaoRegistrationCookie;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
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

class AuthControllerEmailVerificationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthService authService = mock(AuthService.class);
    private final AuthController controller = new AuthController(authService, mock(AccountFindService.class), new KakaoRegistrationCookie(false));

    @Test
    void sendCodeResponseUsesResultContract() throws Exception {
        SignupEmailCodeRequest request = new SignupEmailCodeRequest();
        ReflectionTestUtils.setField(request, "email", "newuser@aewol.com");
        when(authService.sendSignupVerificationCode(request))
                .thenReturn(new SignupEmailCodeResponse(300L));

        ResponseEntity<ApiResponse<SignupEmailCodeResponse>> entity =
                controller.sendSignupVerificationCode(request);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(entity.getBody()));

        assertEquals(200, json.get("status").asInt());
        assertEquals("회원가입 인증번호가 발송되었습니다.", json.get("message").asText());
        assertEquals(300L, json.get("result").get("expiresInSeconds").asLong());
        verify(authService).sendSignupVerificationCode(request);
    }

    @Test
    void sendCodeMailFailureReturnsStandardServiceUnavailableResponse() throws Exception {
        when(authService.sendSignupVerificationCode(any()))
                .thenThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "인증 이메일을 발송할 수 없습니다. 잠시 후 다시 시도해주세요."));
        MockMvc mockMvc = mockMvc();

        String body = mockMvc.perform(post("/api/auth/signup/send-code")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);

        assertEquals(503, json.get("status").asInt());
        assertEquals("인증 이메일을 발송할 수 없습니다. 잠시 후 다시 시도해주세요.",
                json.get("message").asText());
        assertTrue(json.get("result").isNull());
    }

    @Test
    void sendCodeRateLimitReturnsStandardTooManyRequestsResponse() throws Exception {
        when(authService.sendSignupVerificationCode(any()))
                .thenThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                        "회원가입 인증번호 요청이 너무 많습니다. 30분 후 다시 시도해주세요."));

        String body = mockMvc().perform(post("/api/auth/signup/send-code")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);

        assertEquals(429, json.get("status").asInt());
        assertEquals("회원가입 인증번호 요청이 너무 많습니다. 30분 후 다시 시도해주세요.",
                json.get("message").asText());
        assertTrue(json.get("result").isNull());
    }

    @Test
    void verifyCodeResponseIncludesNullResult() throws Exception {
        SignupEmailVerificationRequest request = new SignupEmailVerificationRequest();
        ReflectionTestUtils.setField(request, "email", "newuser@aewol.com");
        ReflectionTestUtils.setField(request, "verificationCode", "123456");

        ResponseEntity<ApiResponse<Void>> entity = controller.verifySignupEmailCode(request);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(entity.getBody()));

        assertEquals(200, json.get("status").asInt());
        assertEquals("이메일 인증이 완료되었습니다.", json.get("message").asText());
        assertTrue(json.has("result"));
        assertTrue(json.get("result").isNull());
        verify(authService).verifySignupEmailCode(request);
    }

    @Test
    void signupReturnsCreatedResponseWithoutTokens() throws Exception {
        SignupRequest request = new SignupRequest();
        SignupResponse response = new SignupResponse(1L, "user@example.com", "홍길동");
        when(authService.signup(request)).thenReturn(response);

        ResponseEntity<ApiResponse<SignupResponse>> entity = controller.signup(request);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(entity.getBody()));

        assertEquals(201, entity.getStatusCodeValue());
        assertEquals(201, json.get("status").asInt());
        assertEquals("회원가입이 완료되었습니다.", json.get("message").asText());
        assertEquals(1L, json.get("result").get("userId").asLong());
        assertEquals("user@example.com", json.get("result").get("email").asText());
        assertEquals("홍길동", json.get("result").get("name").asText());
        assertTrue(!json.get("result").has("accessToken"));
        verify(authService).signup(request);
    }

    @Test
    void legacySignupVerifyMappingIsNotExposed() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/auth/signup/verify")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void signupSendCodeAcceptsEmailAtMaxLength() throws Exception {
        String email = validEmailOfLength(100);
        when(authService.sendSignupVerificationCode(any()))
                .thenReturn(new SignupEmailCodeResponse(300L));

        mockMvc().perform(post("/api/auth/signup/send-code")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        assertEquals(100, email.length());
        verify(authService).sendSignupVerificationCode(any());
    }

    @Test
    void signupSendCodeRejectsEmailOverMaxLengthBeforeService() throws Exception {
        String email = validEmailOfLength(101);

        mockMvc().perform(post("/api/auth/signup/send-code")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(101, email.length());
        verifyNoInteractions(authService);
    }

    @Test
    void signupVerifyCodeAcceptsEmailAtMaxLength() throws Exception {
        String email = validEmailOfLength(100);

        mockMvc().perform(post("/api/auth/signup/verify-code")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email
                                + "\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isOk());

        assertEquals(100, email.length());
        verify(authService).verifySignupEmailCode(any());
    }

    @Test
    void signupVerifyCodeRejectsEmailOverMaxLengthBeforeService() throws Exception {
        String email = validEmailOfLength(101);

        mockMvc().perform(post("/api/auth/signup/verify-code")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email
                                + "\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(101, email.length());
        verifyNoInteractions(authService);
    }

    @Test
    void loginAcceptsEmailAtMaxLength() throws Exception {
        String email = validEmailOfLength(100);
        when(authService.login(any())).thenReturn(new TokenResponse("access-token", "refresh-token"));

        mockMvc().perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"password\"}"))
                .andExpect(status().isOk());

        assertEquals(100, email.length());
        verify(authService).login(any());
    }

    @Test
    void loginRejectsEmailOverMaxLengthBeforeService() throws Exception {
        String email = validEmailOfLength(101);

        mockMvc().perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(101, email.length());
        verifyNoInteractions(authService);
    }

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private String validEmailOfLength(int length) {
        String localPart = "a".repeat(64);
        String topLevelDomain = ".com";
        return localPart + "@" + "b".repeat(length - 69) + topLevelDomain;
    }
}
