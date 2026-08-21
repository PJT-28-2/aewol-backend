package com.aewol.domain.wallet.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.service.TossChargeService;
import com.aewol.domain.wallet.service.WalletService;
import com.aewol.domain.wallet.service.WalletWithdrawalService;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WalletDepositControllerTest {

    @Mock WalletService walletService;
    @Mock TossChargeService tossChargeService;
    @Mock WalletWithdrawalService walletWithdrawalService;

    private WalletController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new WalletController(walletService, tossChargeService, walletWithdrawalService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
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
    void should_returnForbidden_whenUnverifiedDepositIsDisabled() throws Exception {
        mockMvc.perform(post("/api/wallet/deposit").param("amount", "10000"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(walletService);
    }

    @Test
    void should_creditWallet_whenUnverifiedDepositIsAllowed() throws Exception {
        ReflectionTestUtils.setField(controller, "allowUnverifiedDeposit", true);
        when(walletService.deposit("member-1", new BigDecimal("10000")))
                .thenReturn(WalletResponse.builder()
                        .walletId("1")
                        .memberId("member-1")
                        .totalBalance(new BigDecimal("10000"))
                        .build());

        mockMvc.perform(post("/api/wallet/deposit").param("amount", "10000"))
                .andExpect(status().isOk());

        verify(walletService).deposit("member-1", new BigDecimal("10000"));
    }
}
