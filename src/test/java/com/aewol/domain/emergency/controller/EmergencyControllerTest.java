package com.aewol.domain.emergency.controller;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.emergency.dto.HospitalDetailResponse;
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

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401/403 인증 거부는 단위테스트로 검증하지 않는다 (standaloneSetup은 Security 필터체인을
 * 거치지 않아 거짓 양성이 된다) — .omc/plans/emergency-hospital-sos-plan.md Step A6 결정 사항.
 * 인증 거부 확인은 로컬 통합 확인(Verification step 3)에서 수행한다.
 *
 * @Validated 파라미터 제약조건도 같은 이유로 MockMvc로 검증하지 않는다 — standaloneSetup은
 * MethodValidationPostProcessor가 만드는 Spring AOP 프록시를 거치지 않으므로, latitude=999 같은
 * 잘못된 값을 보내도 400이 나오지 않고 그대로 컨트롤러 메서드가 호출된다. 대신 Bean Validation API로
 * 제약조건 애노테이션 자체가 위반을 잡아내는지 직접 검증한다.
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
        verify(emergencyService).searchNearby(33.45, 126.56, 5.0, false);
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

        verify(emergencyService).searchNearby(33.45, 126.56, 5.0, true);
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

        verify(emergencyService).searchNearby(33.45, 126.56, 5.0, false);
    }

    private HospitalDetailResponse sampleHospitalDetail() {
        return HospitalDetailResponse.builder()
                .hospitalId(1L)
                .name("애월동물병원")
                .address("제주시 애월읍 123")
                .phone("064-000-0000")
                .latitude(new BigDecimal("33.4500000"))
                .longitude(new BigDecimal("126.5600000"))
                .is24h(true)
                .isHolidayOpen(false)
                .avgWaitMinutes(15)
                .updatedAt(LocalDateTime.of(2026, 8, 1, 10, 30))
                .build();
    }

    @Test
    @DisplayName("GET /api/emergency/hospitals/{hospitalId}는 병원 상세 정보를 200으로 반환한다")
    void should_return200WithHospitalDetail_when_hospitalExists() throws Exception {
        when(emergencyService.getDetail(1L))
                .thenReturn(sampleHospitalDetail());

        MvcResult result = mockMvc.perform(get("/api/emergency/hospitals/{hospitalId}", 1L))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resultNode = resultNode(result);
        assertEquals("애월동물병원", resultNode.path("name").asText());
        assertTrue(resultNode.path("is24h").asBoolean());
        assertFalse(resultNode.path("isHolidayOpen").asBoolean());
        verify(emergencyService).getDetail(1L);
    }

    @Test
    @DisplayName("존재하지 않는 hospitalId 조회 시 404를 반환한다")
    void should_return404_when_hospitalDoesNotExist() throws Exception {
        when(emergencyService.getDetail(999L))
                .thenThrow(BusinessException.notFound("존재하지 않는 병원입니다."));

        mockMvc.perform(get("/api/emergency/hospitals/{hospitalId}", 999L))
                .andExpect(status().isNotFound());
    }

    private Set<ConstraintViolation<EmergencyController>> validateSearchNearbyParams(Object[] args) throws Exception {
        Validator baseValidator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator validator = baseValidator.forExecutables();
        EmergencyController controller = new EmergencyController(emergencyService);
        Method method = EmergencyController.class.getMethod(
                "searchNearby", double.class, double.class, double.class, boolean.class);
        return validator.validateParameters(controller, method, args);
    }

    @Test
    @DisplayName("latitude가 -90~90 범위를 벗어나면 ConstraintViolation이 발생한다")
    void should_violateConstraint_when_latitudeOutOfRange() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateSearchNearbyParams(new Object[]{999.0, 126.56, 5.0, false});

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("longitude가 -180~180 범위를 벗어나면 ConstraintViolation이 발생한다")
    void should_violateConstraint_when_longitudeOutOfRange() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateSearchNearbyParams(new Object[]{33.45, 999.0, 5.0, false});

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("radiusKm이 0 이하이면 ConstraintViolation이 발생한다")
    void should_violateConstraint_when_radiusKmNotPositive() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateSearchNearbyParams(new Object[]{33.45, 126.56, -1.0, false});

        assertFalse(violations.isEmpty());
    }

    /*
     * 상한이 없으면 바운딩박스 선필터가 무의미해진다. 반경을 크게 넣는 것만으로
     * 전체 스캔을 유발할 수 있다.
     */
    @Test
    @DisplayName("radiusKm이 상한을 넘으면 ConstraintViolation이 발생한다")
    void should_violateConstraint_when_radiusKmTooLarge() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateSearchNearbyParams(new Object[]{33.45, 126.56, 99999.0, false});

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("유효 범위 내 값은 ConstraintViolation이 발생하지 않는다")
    void should_haveNoViolation_when_paramsWithinRange() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateSearchNearbyParams(new Object[]{33.45, 126.56, 5.0, false});

        assertTrue(violations.isEmpty());
    }

    private Set<ConstraintViolation<EmergencyController>> validateGetDetailParams(Object[] args) throws Exception {
        Validator baseValidator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator validator = baseValidator.forExecutables();
        EmergencyController controller = new EmergencyController(emergencyService);
        Method method = EmergencyController.class.getMethod("getDetail", Long.class);
        return validator.validateParameters(controller, method, args);
    }

    @Test
    @DisplayName("hospitalId가 0이면 ConstraintViolation이 발생한다")
    void should_violateConstraint_when_hospitalIdIsZero() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateGetDetailParams(new Object[]{0L});

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("hospitalId가 음수이면 ConstraintViolation이 발생한다")
    void should_violateConstraint_when_hospitalIdIsNegative() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateGetDetailParams(new Object[]{-1L});

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("hospitalId가 1 이상이면 ConstraintViolation이 발생하지 않는다")
    void should_haveNoViolation_when_hospitalIdIsPositive() throws Exception {
        Set<ConstraintViolation<EmergencyController>> violations =
                validateGetDetailParams(new Object[]{1L});

        assertTrue(violations.isEmpty());
    }
}
