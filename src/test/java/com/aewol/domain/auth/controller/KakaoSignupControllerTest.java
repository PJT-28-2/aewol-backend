package com.aewol.domain.auth.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeResponse;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.service.KakaoSignupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class KakaoSignupControllerTest {

    private static final String TOKEN = "a".repeat(43);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private KakaoSignupService kakaoSignupService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kakaoSignupService = Mockito.mock(KakaoSignupService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new KakaoSignupController(kakaoSignupService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void threeEndpointsUseExistingApiResponseConvention() throws Exception {
        when(kakaoSignupService.sendPhoneVerificationCode(any()))
                .thenReturn(new KakaoPhoneSendCodeResponse(300L));
        when(kakaoSignupService.complete(any())).thenReturn(
                KakaoOAuthResponse.loginComplete(TokenResponse.builder()
                        .accessToken("access-token")
                        .refreshToken("refresh-token")
                        .build()));

        JsonNode sent = json(mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/send-code")
                        .contentType("application/json")
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"phone\":\"01012345678\"}"))
                .andReturn());
        JsonNode verified = json(mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/verify-code")
                        .contentType("application/json")
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"verificationCode\":\"123456\"}"))
                .andReturn());
        MvcResult completedResult = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/complete")
                        .contentType("application/json")
                        .content(completeBody(true, true)))
                .andReturn();
        JsonNode completed = json(completedResult);

        assertEquals(200, sent.get("status").asInt());
        assertEquals("인증번호가 발송되었습니다.", sent.get("message").asText());
        assertEquals(300L, sent.get("result").get("expiresInSeconds").asLong());
        assertEquals(200, verified.get("status").asInt());
        assertTrue(verified.get("result").isNull());
        assertEquals(HttpStatus.CREATED.value(), completedResult.getResponse().getStatus());
        assertEquals(201, completed.get("status").asInt());
        assertEquals("LOGIN_COMPLETE",
                completed.get("result").get("authStatus").asText());
        assertEquals("access-token",
                completed.get("result").get("accessToken").asText());
        assertEquals("refresh-token",
                completed.get("result").get("refreshToken").asText());
        assertTrue(completed.get("result").get("registrationToken").isNull());
    }

    @Test
    void malformedTokenPhoneAndOtpAreRejectedBeforeService() throws Exception {
        assertBadRequest("/api/auth/oauth/kakao/signup/phone/send-code",
                "{\"registrationToken\":\"short\",\"phone\":\"01012345678\"}");
        assertBadRequest("/api/auth/oauth/kakao/signup/phone/send-code",
                "{\"registrationToken\":\"" + TOKEN
                        + "\",\"phone\":\"010-1234-5678\"}");
        assertBadRequest("/api/auth/oauth/kakao/signup/phone/verify-code",
                "{\"registrationToken\":\"" + TOKEN
                        + "\",\"verificationCode\":\"12345\"}");

        verifyNoInteractions(kakaoSignupService);
    }

    @Test
    void falseTermsAndPrivacyAreRejectedBeforeService() throws Exception {
        assertBadRequest("/api/auth/oauth/kakao/signup/complete", completeBody(false, true));
        assertBadRequest("/api/auth/oauth/kakao/signup/complete", completeBody(true, false));

        verifyNoInteractions(kakaoSignupService);
    }

    @Test
    void completeDoesNotAcceptClientControlledPhone() throws Exception {
        String body = completeBody(true, true).replace(
                "\"marketing\":false", "\"marketing\":false,\"phone\":\"01099999999\"");

        assertBadRequest("/api/auth/oauth/kakao/signup/complete", body);

        verifyNoInteractions(kakaoSignupService);
    }

    @Test
    void addressDetailMatchesMemberColumnBoundary() throws Exception {
        MvcResult accepted = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/complete")
                        .contentType("application/json")
                        .content(completeBody(true, true, "가".repeat(100))))
                .andReturn();

        assertEquals(HttpStatus.CREATED.value(), accepted.getResponse().getStatus());
        verify(kakaoSignupService).complete(any());

        assertBadRequest("/api/auth/oauth/kakao/signup/complete",
                completeBody(true, true, "가".repeat(101)));
    }

    private String completeBody(boolean terms, boolean privacy) {
        return completeBody(terms, privacy, "101호");
    }

    private String completeBody(boolean terms, boolean privacy, String addressDetail) {
        return "{\"registrationToken\":\"" + TOKEN + "\"," 
                + "\"zipCode\":\"12345\"," 
                + "\"address\":\"제주시 애월읍\"," 
                + "\"addressDetail\":\"" + addressDetail + "\"," 
                + "\"terms\":" + terms + "," 
                + "\"privacy\":" + privacy + ","
                + "\"marketing\":false}";
    }

    private void assertBadRequest(String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .contentType("application/json")
                        .content(body))
                .andReturn();
        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
