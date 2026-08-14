package com.aewol.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.auth.dto.AccountFindResultResponse;
import com.aewol.domain.auth.dto.AccountFindSendCodeResponse;
import com.aewol.domain.auth.service.AccountFindService;
import com.aewol.domain.auth.service.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class AuthControllerAccountFindTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AccountFindService accountFindService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountFindService = mock(AccountFindService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(mock(AuthService.class), accountFindService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void sendCodeReturnsGeneralizedContract() throws Exception {
        when(accountFindService.sendVerificationCode(any()))
                .thenReturn(new AccountFindSendCodeResponse("opaque-request-id", 300L));

        MvcResult result = mockMvc.perform(post("/api/auth/account/find/send-code")
                        .contentType("application/json")
                        .content("{\"name\":\"홍길동\",\"phone\":\"01012345678\"}"))
                .andReturn();
        assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus());
        JsonNode json = json(result);

        assertEquals(200, json.get("status").asInt());
        assertEquals("입력하신 정보가 등록된 계정과 일치하면 인증번호가 발송됩니다.",
                json.get("message").asText());
        assertEquals("opaque-request-id", json.get("result").get("requestId").asText());
        assertEquals(300L, json.get("result").get("expiresInSeconds").asLong());
    }

    @Test
    void verifyCodeKeepsSameShapeForLocalAndKakao() throws Exception {
        when(accountFindService.verifyCode(any()))
                .thenReturn(new AccountFindResultResponse("LOCAL", "hong****@example.com"))
                .thenReturn(new AccountFindResultResponse("KAKAO", null));

        JsonNode local = verifyResponse();
        JsonNode kakao = verifyResponse();

        assertEquals("LOCAL", local.get("result").get("provider").asText());
        assertEquals("hong****@example.com", local.get("result").get("maskedEmail").asText());
        assertEquals("KAKAO", kakao.get("result").get("provider").asText());
        assertTrue(kakao.get("result").has("maskedEmail"));
        assertTrue(kakao.get("result").get("maskedEmail").isNull());
    }

    @Test
    void malformedPhoneAndOtpAreRejectedBeforeService() throws Exception {
        MvcResult malformedPhone = mockMvc.perform(post("/api/auth/account/find/send-code")
                        .contentType("application/json")
                        .content("{\"name\":\"홍길동\",\"phone\":\"010-1234-5678\"}"))
                .andReturn();
        MvcResult malformedOtp = mockMvc.perform(post("/api/auth/account/find/verify-code")
                        .contentType("application/json")
                        .content("{\"requestId\":\"opaque\",\"verificationCode\":\"12345\"}"))
                .andReturn();
        assertEquals(HttpStatus.BAD_REQUEST.value(), malformedPhone.getResponse().getStatus());
        assertEquals(HttpStatus.BAD_REQUEST.value(), malformedOtp.getResponse().getStatus());
        verifyNoInteractions(accountFindService);
    }

    @Test
    void rateLimitAndProviderFailureUseExistingErrorContract() throws Exception {
        when(accountFindService.sendVerificationCode(any()))
                .thenThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "요청 제한"))
                .thenThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "발송 실패"));

        JsonNode limited = sendResponse(HttpStatus.TOO_MANY_REQUESTS.value());
        JsonNode unavailable = sendResponse(HttpStatus.SERVICE_UNAVAILABLE.value());

        assertEquals(429, limited.get("status").asInt());
        assertEquals(503, unavailable.get("status").asInt());
    }

    private JsonNode sendResponse(int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/account/find/send-code")
                        .contentType("application/json")
                        .content("{\"name\":\"홍길동\",\"phone\":\"01012345678\"}"))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus());
        return json(result);
    }

    private JsonNode verifyResponse() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/account/find/verify-code")
                        .contentType("application/json")
                        .content("{\"requestId\":\"123e4567-e89b-12d3-a456-426614174000\","
                                + "\"verificationCode\":\"123456\"}"))
                .andReturn();
        assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus());
        return json(result);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
