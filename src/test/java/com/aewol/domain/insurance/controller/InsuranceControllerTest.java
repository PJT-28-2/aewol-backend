package com.aewol.domain.insurance.controller;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.service.ClaimService;
import com.aewol.domain.insurance.service.InsuranceProductService;
import com.aewol.domain.insurance.service.InsuranceSimulationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장소 전체에 *ControllerTest가 없어 신규로 도입하는 테스트 카테고리.
 * MockMvc.standaloneSetup은 @AuthenticationPrincipal을 기본 해석하지 못하므로
 * AuthenticationPrincipalArgumentResolver를 커스텀 리졸버로 등록한다.
 * jsonPath()는 com.jayway.jsonpath 의존성이 없어 사용하지 않고, 응답 본문을
 * Jackson ObjectMapper로 직접 파싱해 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InsuranceControllerTest {

    @Mock InsuranceSimulationService simulationService;
    @Mock InsuranceProductService productService;
    @Mock ClaimService claimService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        InsuranceController controller = new InsuranceController(simulationService, productService, claimService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("100", null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ClaimResponse sampleClaim(String claimId) {
        return ClaimResponse.builder()
                .claimId(claimId)
                .petId("10")
                .claimStatus("DRAFT")
                .extractedData("{}")
                .build();
    }

    private JsonNode resultNode(MvcResult mvcResult) throws Exception {
        JsonNode body = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        return body.path("result");
    }

    @Test
    @DisplayName("POST /api/insurance/claims는 청구를 생성하고 201을 반환한다")
    void should_return201_whenCreatingClaim() throws Exception {
        when(claimService.createClaim(eq("100"), eq("10"), any())).thenReturn(sampleClaim("1"));

        MockMultipartFile receipt = new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg", "img".getBytes());

        MvcResult result = mockMvc.perform(multipart("/api/insurance/claims")
                        .file(receipt)
                        .param("petId", "10"))
                .andExpect(status().isCreated())
                .andReturn();

        assertEquals("1", resultNode(result).path("claimId").asText());
    }

    @Test
    @DisplayName("GET /api/insurance/claims/{claimId}는 본인 청구를 200으로 반환한다")
    void should_return200_whenGettingOwnClaim() throws Exception {
        when(claimService.getClaim("100", "1")).thenReturn(sampleClaim("1"));

        MvcResult result = mockMvc.perform(get("/api/insurance/claims/1"))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals("1", resultNode(result).path("claimId").asText());
    }

    @Test
    @DisplayName("GET /api/insurance/claims/{claimId}는 타인 청구 조회 시 404를 반환한다")
    void should_return404_whenGettingOthersClaim() throws Exception {
        when(claimService.getClaim("100", "1"))
                .thenThrow(BusinessException.notFound("청구 정보를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/insurance/claims/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/insurance/claims/{claimId}/confirm은 body가 있으면 200을 반환한다")
    void should_return200_whenConfirmingWithBody() throws Exception {
        ClaimResponse corrected = ClaimResponse.builder()
                .hospitalName("애월동물병원")
                .treatmentDate("2026-01-01")
                .totalAmount(new BigDecimal("15000"))
                .build();
        when(claimService.confirmClaim(eq("100"), eq("1"), any())).thenReturn(sampleClaim("1"));

        mockMvc.perform(post("/api/insurance/claims/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(corrected)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/insurance/claims/{claimId}/confirm은 body 없이도 500이 아닌 200을 반환한다")
    void should_return200_whenConfirmingWithoutBody() throws Exception {
        when(claimService.confirmClaim(eq("100"), eq("1"), isNull())).thenReturn(sampleClaim("1"));

        mockMvc.perform(post("/api/insurance/claims/1/confirm"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/insurance/claims/{claimId}/confirm은 타인 청구에 대해 404를 반환한다")
    void should_return404_whenConfirmingOthersClaim() throws Exception {
        when(claimService.confirmClaim(eq("100"), eq("1"), isNull()))
                .thenThrow(BusinessException.notFound("청구 정보를 찾을 수 없습니다."));

        mockMvc.perform(post("/api/insurance/claims/1/confirm"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("annualMedicalCostKrw가 0이면 400을 반환한다 (0원은 전 상품을 기계적으로 불리하게 만든다)")
    void should_return400_whenAnnualMedicalCostIsZero() throws Exception {
        mockMvc.perform(post("/api/insurance/simulations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petId\":\"1\",\"medicalHistoryCodes\":[\"NONE\"],\"annualMedicalCostKrw\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검증 실패 메시지는 Accept-Language와 무관하게 한글로 나간다")
    void should_returnKoreanValidationMessage_regardlessOfAcceptLanguage() throws Exception {
        // message를 생략하면 Hibernate Validator 기본 번역이 Accept-Language를 따라가
        // en-US에서는 "must be greater than or equal to ..."가 나갔다. 한 응답 안에서
        // 커스텀 제약(한글 고정)과 언어가 섞이는 문제라 명시 메시지로 고정했다.
        for (String lang : new String[] {"en-US", "ja-JP", "ko-KR"}) {
            MvcResult result = mockMvc.perform(post("/api/insurance/simulations")
                            .header("Accept-Language", lang)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"petId\":\"1\",\"medicalHistoryCodes\":[\"NONE\"],\"annualMedicalCostKrw\":0}"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            // standaloneSetup의 MockHttpServletResponse 기본 charset은 ISO-8859-1이라
            // 인자 없는 getContentAsString()으로 읽으면 한글이 깨진다. UTF-8을 명시한다.
            String message = objectMapper.readTree(
                            result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("message").asText();
            assertEquals("annualMedicalCostKrw는 10,000원 이상이어야 합니다.",
                    message.replaceFirst("^annualMedicalCostKrw: ", ""),
                    "Accept-Language=" + lang + " 에서 한글 메시지가 아니었다: " + message);
        }
    }
}
