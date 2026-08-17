package com.aewol.domain.emergency.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.emergency.service.HospitalSeedRunner;
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

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401/403 인증 거부는 단위테스트로 검증하지 않는다 — standaloneSetup은 Security 필터체인을
 * 거치지 않아 거짓 양성이 된다(EmergencyControllerTest와 동일한 이유). 이 엔드포인트의
 * ROLE_ADMIN 요구는 SecurityConfig의 {@code /api/admin/**} 규칙으로 적용되며, 실제 거부 여부는
 * 로컬 통합 확인에서 점검한다.
 */
@ExtendWith(MockitoExtension.class)
class HospitalSeedControllerTest {

    @Mock
    HospitalSeedRunner hospitalSeedRunner;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        HospitalSeedController controller = new HospitalSeedController(hospitalSeedRunner);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private JsonNode body(MvcResult mvcResult) throws Exception {
        return objectMapper.readTree(mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("시딩을 시작하면 202와 안내 메시지를 반환한다")
    void should_return202_when_seedingStarted() throws Exception {
        when(hospitalSeedRunner.start()).thenReturn(HospitalSeedRunner.StartResult.STARTED);

        MvcResult result = mockMvc.perform(post("/api/admin/emergency/hospitals/sync"))
                .andExpect(status().isAccepted())
                .andReturn();

        assertEquals(202, body(result).get("status").asInt());
        assertEquals("동물병원 데이터 시딩을 시작했습니다. 진행 상황은 서버 로그를 확인하세요.",
                body(result).get("message").asText());
        verify(hospitalSeedRunner).start();
    }

    @Test
    @DisplayName("이미 실행 중이면 409를 반환한다")
    void should_return409_when_seedingAlreadyRunning() throws Exception {
        when(hospitalSeedRunner.start()).thenReturn(HospitalSeedRunner.StartResult.ALREADY_RUNNING);

        MvcResult result = mockMvc.perform(post("/api/admin/emergency/hospitals/sync"))
                .andExpect(status().isConflict())
                .andReturn();

        assertEquals(409, body(result).get("status").asInt());
        assertEquals("동물병원 데이터 시딩이 이미 실행 중입니다.", body(result).get("message").asText());
    }

    @Test
    @DisplayName("service-key가 없으면 503을 반환한다")
    void should_return503_when_serviceKeyIsMissing() throws Exception {
        when(hospitalSeedRunner.start()).thenReturn(HospitalSeedRunner.StartResult.NOT_CONFIGURED);

        MvcResult result = mockMvc.perform(post("/api/admin/emergency/hospitals/sync"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertEquals(503, body(result).get("status").asInt());
        assertEquals("동물병원 공공데이터 service-key가 설정되지 않아 시딩을 실행할 수 없습니다.",
                body(result).get("message").asText());
    }
}
