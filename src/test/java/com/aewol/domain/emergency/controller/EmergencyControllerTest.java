package com.aewol.domain.emergency.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.emergency.dto.HospitalResponse;
import com.aewol.domain.emergency.service.EmergencyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401/403 인증 거부는 단위테스트로 검증하지 않는다 (standaloneSetup은 Security 필터체인을
 * 거치지 않아 거짓 양성이 된다) — .omc/plans/emergency-hospital-sos-plan.md Step A6 결정 사항.
 * 인증 거부 확인은 로컬 통합 확인(Verification step 3)에서 수행한다.
 */
@ExtendWith(MockitoExtension.class)
class EmergencyControllerTest {

    @Mock
    EmergencyService emergencyService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        EmergencyController controller = new EmergencyController(emergencyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private JsonNode resultNode(MvcResult mvcResult) throws Exception {
        JsonNode body = objectMapper.readTree(mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("result");
    }

    private HospitalResponse sampleHospital() {
        return HospitalResponse.builder()
                .name("애월동물병원")
                .address("제주시 애월읍 123")
                .phone("064-000-0000")
                .latitude(new BigDecimal("33.4500000"))
                .longitude(new BigDecimal("126.5600000"))
                .distanceKm(new BigDecimal("1.2"))
                .build();
    }

    @Test
    @DisplayName("GET /api/emergency/hospitals는 거리순 병원 목록을 200으로 반환한다")
    void should_return200WithHospitalList_when_searchingNearby() throws Exception {
        when(emergencyService.searchNearby(33.45, 126.56, 5.0, false))
                .thenReturn(List.of(sampleHospital()));

        MvcResult result = mockMvc.perform(get("/api/emergency/hospitals")
                        .param("latitude", "33.45")
                        .param("longitude", "126.56"))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals("애월동물병원", resultNode(result).get(0).path("name").asText());
    }

    @Test
    @DisplayName("is24h=true 파라미터는 서비스에 그대로 전달된다")
    void should_passIs24hTrue_when_queryParamSet() throws Exception {
        when(emergencyService.searchNearby(eq(33.45), eq(126.56), eq(5.0), eq(true)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/emergency/hospitals")
                        .param("latitude", "33.45")
                        .param("longitude", "126.56")
                        .param("is24h", "true"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("radiusKm 파라미터를 생략하면 기본값 5km가 적용된다")
    void should_useDefaultRadius_when_radiusKmOmitted() throws Exception {
        when(emergencyService.searchNearby(eq(33.45), eq(126.56), eq(5.0), eq(false)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/emergency/hospitals")
                        .param("latitude", "33.45")
                        .param("longitude", "126.56"))
                .andExpect(status().isOk());
    }
}
