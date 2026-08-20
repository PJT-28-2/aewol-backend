package com.aewol.domain.auth.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.auth.service.AccountFindService;
import com.aewol.domain.auth.support.KakaoRegistrationCookie;
import com.aewol.domain.auth.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerSignupPhoneValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService, mock(AccountFindService.class), new KakaoRegistrationCookie(false));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingPhoneIsRejectedBeforeService() throws Exception {
        ObjectNode body = validSignupBody();
        body.remove("phone");

        assertBadRequest(body);
    }

    @Test
    void nullPhoneIsRejectedBeforeService() throws Exception {
        ObjectNode body = validSignupBody();
        body.putNull("phone");

        assertBadRequest(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "123",
            "999999",
            "010-1234-5678",
            "01112345678",
            "0101234567",
            "010123456789"
    })
    void invalidPhoneIsRejectedBeforeService(String phone) throws Exception {
        ObjectNode body = validSignupBody();
        body.put("phone", phone);

        assertBadRequest(body);
    }

    @Test
    void digitsOnlyElevenDigit010PhoneIsAccepted() throws Exception {
        when(authService.signup(any())).thenReturn(
                new SignupResponse(1L, "user@example.com", "홍길동"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validSignupBody())))
                .andExpect(status().isCreated());

        ArgumentCaptor<com.aewol.domain.auth.dto.SignupRequest> requestCaptor =
                ArgumentCaptor.forClass(com.aewol.domain.auth.dto.SignupRequest.class);
        verify(authService).signup(requestCaptor.capture());
        assertEquals("01012345678", requestCaptor.getValue().getPhone());
    }

    private void assertBadRequest(ObjectNode body) throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    private ObjectNode validSignupBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", "user@example.com");
        body.put("verificationCode", "123456");
        body.put("password", "Abcdef1!");
        body.put("name", "홍길동");
        body.put("phone", "01012345678");
        body.put("zipCode", "12345");
        body.put("address", "제주시 애월읍");
        body.put("addressDetail", "101호");
        body.put("terms", true);
        body.put("privacy", true);
        body.put("marketing", false);
        return body;
    }
}
