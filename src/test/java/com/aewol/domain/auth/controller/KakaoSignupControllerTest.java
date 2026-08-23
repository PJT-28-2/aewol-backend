package com.aewol.domain.auth.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.ErrorCode;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeResponse;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.service.KakaoSignupService;
import com.aewol.domain.auth.support.KakaoRegistrationCookie;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import javax.servlet.http.Cookie;

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
                        new KakaoSignupController(kakaoSignupService, new KakaoRegistrationCookie(false)))
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
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"phone\":\"01012345678\"}"))
                .andReturn());
        JsonNode verified = json(mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/verify-code")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"verificationCode\":\"123456\"}"))
                .andReturn());
        MvcResult completedResult = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/complete")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
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
        assertFalse(sent.has("errorCode"));
        assertFalse(verified.has("errorCode"));
        assertFalse(completed.has("errorCode"));
    }

    @Test
    void signupRejectsWhenRegistrationCookieDoesNotMatchBody() throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/send-code")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, "b".repeat(43)))
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"phone\":\"01012345678\"}"))
                .andReturn();

        assertEquals(HttpStatus.UNAUTHORIZED.value(), result.getResponse().getStatus());
        assertTerminalSessionError(result, HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(kakaoSignupService);
    }

    @Test
    void threeEndpointsReturnCodedUnauthorizedWhenRegistrationCookieIsMissing() throws Exception {
        MvcResult sent = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/send-code")
                        .contentType("application/json")
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"phone\":\"01012345678\"}"))
                .andReturn();
        MvcResult verified = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/verify-code")
                        .contentType("application/json")
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"verificationCode\":\"123456\"}"))
                .andReturn();
        MvcResult completed = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/complete")
                        .contentType("application/json")
                        .content(completeBody(true, true)))
                .andReturn();

        assertTerminalSessionError(sent, HttpStatus.UNAUTHORIZED);
        assertTerminalSessionError(verified, HttpStatus.UNAUTHORIZED);
        assertTerminalSessionError(completed, HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(kakaoSignupService);
    }

    @Test
    void threeEndpointsExposeCodedBadRequestForMissingRegistrationSession() throws Exception {
        when(kakaoSignupService.sendPhoneVerificationCode(any()))
                .thenThrow(invalidRegistrationSession());
        doThrow(invalidRegistrationSession()).when(kakaoSignupService).verifyPhoneCode(any());
        when(kakaoSignupService.complete(any())).thenThrow(invalidRegistrationSession());

        MvcResult sent = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/send-code")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"phone\":\"01012345678\"}"))
                .andReturn();
        MvcResult verified = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/verify-code")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"verificationCode\":\"123456\"}"))
                .andReturn();
        MvcResult completed = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/complete")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content(completeBody(true, true)))
                .andReturn();

        assertTerminalSessionError(sent, HttpStatus.BAD_REQUEST);
        assertTerminalSessionError(verified, HttpStatus.BAD_REQUEST);
        assertTerminalSessionError(completed, HttpStatus.BAD_REQUEST);
    }

    @Test
    void ordinaryKakaoErrorsDoNotExposeRegistrationSessionErrorCode() throws Exception {
        doThrow(new BusinessException(HttpStatus.BAD_REQUEST, "인증번호가 유효하지 않습니다."))
                .when(kakaoSignupService).verifyPhoneCode(any());
        when(kakaoSignupService.complete(any()))
                .thenThrow(BusinessException.conflict("카카오 가입 요청이 이미 처리 중입니다."));
        when(kakaoSignupService.sendPhoneVerificationCode(any()))
                .thenThrow(new BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE, "카카오 가입을 진행할 수 없습니다."));

        MvcResult otp = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/verify-code")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"verificationCode\":\"123456\"}"))
                .andReturn();
        MvcResult claimed = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/complete")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content(completeBody(true, true)))
                .andReturn();
        MvcResult unavailable = mockMvc.perform(post(
                        "/api/auth/oauth/kakao/signup/phone/send-code")
                        .contentType("application/json")
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
                        .content("{\"registrationToken\":\"" + TOKEN
                                + "\",\"phone\":\"01012345678\"}"))
                .andReturn();

        assertNoErrorCode(otp, HttpStatus.BAD_REQUEST);
        assertNoErrorCode(claimed, HttpStatus.CONFLICT);
        assertNoErrorCode(unavailable, HttpStatus.SERVICE_UNAVAILABLE);
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
                        .cookie(new Cookie(KakaoRegistrationCookie.NAME, TOKEN))
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
        assertFalse(json(result).has("errorCode"));
    }

    private BusinessException invalidRegistrationSession() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "유효하지 않거나 만료된 카카오 가입 세션입니다.",
                ErrorCode.KAKAO_REGISTRATION_SESSION_INVALID_OR_EXPIRED);
    }

    private void assertTerminalSessionError(MvcResult result, HttpStatus status) throws Exception {
        JsonNode body = json(result);
        assertEquals(status.value(), result.getResponse().getStatus());
        assertEquals(status.value(), body.get("status").asInt());
        assertEquals("KAKAO_REGISTRATION_SESSION_INVALID_OR_EXPIRED",
                body.get("errorCode").asText());
    }

    private void assertNoErrorCode(MvcResult result, HttpStatus status) throws Exception {
        JsonNode body = json(result);
        assertEquals(status.value(), result.getResponse().getStatus());
        assertEquals(status.value(), body.get("status").asInt());
        assertFalse(body.has("errorCode"));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
