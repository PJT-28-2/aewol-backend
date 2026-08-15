package com.aewol.domain.auth.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.auth.service.AuthService;
import com.aewol.domain.auth.service.AccountFindService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerEmailVerificationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthService authService = mock(AuthService.class);
    private final AuthController controller = new AuthController(authService, mock(AccountFindService.class));

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
}
