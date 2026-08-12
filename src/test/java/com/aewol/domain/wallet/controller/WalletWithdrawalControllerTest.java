package com.aewol.domain.wallet.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.wallet.dto.WalletWithdrawResponse;
import com.aewol.domain.wallet.service.TossChargeService;
import com.aewol.domain.wallet.service.WalletService;
import com.aewol.domain.wallet.service.WalletWithdrawalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WalletWithdrawalControllerTest {

    @Mock WalletService walletService;
    @Mock TossChargeService tossChargeService;
    @Mock WalletWithdrawalService walletWithdrawalService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WalletController(walletService, tossChargeService, walletWithdrawalService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("member-1", null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void should_returnWithdrawalResult_when_requestIsValid() throws Exception {
        when(walletWithdrawalService.withdraw(eq("member-1"), any())).thenReturn(
                WalletWithdrawResponse.builder()
                        .transactionId("2003")
                        .walletBalance(new BigDecimal("382600"))
                        .accountId("12")
                        .bankName("KB국민은행")
                        .accountNumberMasked("********4444")
                        .withdrawnAt(LocalDateTime.of(2026, 8, 12, 15, 24))
                        .build());

        MvcResult mvcResult = mockMvc.perform(post("/api/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"12\",\"amount\":100000,"
                                + "\"memo\":\"내 계좌로 출금\",\"password\":\"482913\"}"))
                .andExpect(status().isOk()).andReturn();

        JsonNode result = objectMapper.readTree(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("result");
        assertEquals("2003", result.get("transactionId").asText());
        assertEquals(382600, result.get("walletBalance").asInt());
        assertEquals("********4444", result.get("accountNumberMasked").asText());

        verify(walletWithdrawalService).withdraw(eq("member-1"), any());
    }

    @Test
    void should_returnBadRequest_when_amountHasFraction() throws Exception {
        mockMvc.perform(post("/api/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"12\",\"amount\":1000.50,"
                                + "\"password\":\"482913\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(walletWithdrawalService);
    }

    @Test
    void should_returnBadRequest_when_passwordIsNotSixDigits() throws Exception {
        mockMvc.perform(post("/api/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"12\",\"amount\":1000,"
                                + "\"password\":\"12345a\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(walletWithdrawalService);
    }
}
