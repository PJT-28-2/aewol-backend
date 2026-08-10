package com.aewol.domain.recurring.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.recurring.dto.RecurringCreateRequest;
import com.aewol.domain.recurring.dto.RecurringResponse;
import com.aewol.domain.recurring.service.RecurringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RecurringControllerTest {

    @Mock RecurringService recurringService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RecurringController(recurringService))
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
    void should_return200_when_gettingRecurringPayments() throws Exception {
        when(recurringService.getRecurringPayments("member-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/recurring"))
                .andExpect(status().isOk());

        verify(recurringService).getRecurringPayments("member-1");
    }

    @Test
    void should_return201_when_creatingRecurringPayment() throws Exception {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "pet-1");
        when(recurringService.createRecurring(any(), any())).thenReturn(RecurringResponse.builder()
                .recurringId("10")
                .itemName("강아지 사료")
                .price(new BigDecimal("32000"))
                .cycleDay(15)
                .category("FOOD")
                .petId("pet-1")
                .nextPaymentDate("2026-08-15")
                .build());

        mockMvc.perform(post("/api/recurring")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(recurringService).createRecurring(any(), any());
    }

    @Test
    void should_return400_when_createRequestIsInvalid() throws Exception {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", BigDecimal.ZERO, 32, "SOS", null);

        mockMvc.perform(post("/api/recurring")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return200_when_cancellingRecurringPayment() throws Exception {
        mockMvc.perform(delete("/api/recurring/{recurringId}", "10"))
                .andExpect(status().isOk());

        verify(recurringService).cancelRecurring("member-1", "10");
    }
}
