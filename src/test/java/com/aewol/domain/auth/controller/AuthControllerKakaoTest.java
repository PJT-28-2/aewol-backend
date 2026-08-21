package com.aewol.domain.auth.controller;

import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.service.AccountFindService;
import com.aewol.domain.auth.support.KakaoRegistrationCookie;
import com.aewol.domain.auth.service.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerKakaoTest {

    @Mock AuthService authService;
    @Mock AccountFindService accountFindService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(authService, accountFindService, new KakaoRegistrationCookie(false))).build();
    }

    @Test
    void kakaoOAuthResponseHasExplicitStatusAndMutuallyExclusiveTokens() throws Exception {
        when(authService.kakaoLogin("existing-code")).thenReturn(
                KakaoOAuthResponse.loginComplete(TokenResponse.builder()
                        .accessToken("access-token")
                        .refreshToken("refresh-token")
                        .build()));
        when(authService.kakaoLogin("new-code")).thenReturn(
                KakaoOAuthResponse.additionalInfoRequired("registration-token"));

        JsonNode existing = responseFor("existing-code");
        JsonNode newMember = responseFor("new-code");

        assertEquals("LOGIN_COMPLETE", existing.get("authStatus").asText());
        assertEquals("access-token", existing.get("accessToken").asText());
        assertEquals("refresh-token", existing.get("refreshToken").asText());
        assertTrue(existing.get("registrationToken").isNull());

        assertEquals("ADDITIONAL_INFO_REQUIRED", newMember.get("authStatus").asText());
        assertTrue(newMember.get("accessToken").isNull());
        assertTrue(newMember.get("refreshToken").isNull());
        assertEquals("registration-token", newMember.get("registrationToken").asText());
    }

    private JsonNode responseFor(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/oauth/kakao").param("code", code))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("result");
    }
}
