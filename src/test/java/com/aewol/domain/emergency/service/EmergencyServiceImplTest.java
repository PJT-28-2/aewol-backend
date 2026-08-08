package com.aewol.domain.emergency.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.emergency.dto.HospitalDetailResponse;
import com.aewol.domain.emergency.dto.HospitalResponse;
import com.aewol.domain.emergency.mapper.EmergencyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmergencyServiceImplTest {

    @Mock
    EmergencyMapper emergencyMapper;

    @InjectMocks
    EmergencyServiceImpl emergencyService;

    private Map<String, Object> hospitalRow(String name, Object latitude, Object longitude, Object distanceKm) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("address", "제주시 애월읍 123");
        row.put("phone", "064-000-0000");
        row.put("latitude", latitude);
        row.put("longitude", longitude);
        row.put("distance_km", distanceKm);
        return row;
    }

    @Test
    @DisplayName("mapper의 raw Map 결과를 HospitalResponse DTO로 매핑한다")
    void should_mapRowsToHospitalResponse_when_searchingNearby() {
        when(emergencyMapper.findNearby(33.45, 126.56, 5.0, false))
                .thenReturn(List.of(hospitalRow(
                        "애월동물병원", new BigDecimal("33.4500000"), new BigDecimal("126.5600000"), 1.2)));

        List<HospitalResponse> result = emergencyService.searchNearby(33.45, 126.56, 5.0, false);

        assertEquals(1, result.size());
        HospitalResponse response = result.get(0);
        assertEquals("애월동물병원", response.getName());
        assertEquals("제주시 애월읍 123", response.getAddress());
        assertEquals("064-000-0000", response.getPhone());
        assertEquals(new BigDecimal("33.4500000"), response.getLatitude());
        assertEquals(0, new BigDecimal("1.2").compareTo(response.getDistanceKm()));
    }

    @Test
    @DisplayName("is24h=true 요청 시 mapper에 그대로 전달한다")
    void should_passIs24hTrue_when_requested() {
        when(emergencyMapper.findNearby(anyDouble(), anyDouble(), anyDouble(), anyBoolean()))
                .thenReturn(List.of());

        emergencyService.searchNearby(33.45, 126.56, 5.0, true);

        verify(emergencyMapper).findNearby(33.45, 126.56, 5.0, true);
    }

    @Test
    @DisplayName("좌표가 NULL인 행도 예외 없이 매핑한다")
    void should_mapNullCoordinates_when_locationUnknown() {
        when(emergencyMapper.findNearby(anyDouble(), anyDouble(), anyDouble(), anyBoolean()))
                .thenReturn(List.of(hospitalRow("좌표미확인병원", null, null, null)));

        List<HospitalResponse> result = emergencyService.searchNearby(33.45, 126.56, 5.0, false);

        assertNull(result.get(0).getLatitude());
        assertNull(result.get(0).getDistanceKm());
    }

    private Map<String, Object> hospitalDetailRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("hospital_id", 1L);
        row.put("name", "애월동물병원");
        row.put("address", "제주시 애월읍 123");
        row.put("phone", "064-000-0000");
        row.put("latitude", new BigDecimal("33.4500000"));
        row.put("longitude", new BigDecimal("126.5600000"));
        row.put("is_24h", true);
        row.put("is_holiday_open", false);
        row.put("avg_wait_minutes", 15);
        row.put("updated_at", Timestamp.valueOf(LocalDateTime.of(2026, 8, 1, 10, 30)));
        return row;
    }

    @Test
    @DisplayName("mapper의 raw Map 결과를 HospitalDetailResponse DTO로 매핑한다")
    void should_mapRowToHospitalDetailResponse_when_found() {
        when(emergencyMapper.findById(1L))
                .thenReturn(hospitalDetailRow());

        HospitalDetailResponse response = emergencyService.getDetail(1L);

        assertEquals(1L, response.getHospitalId());
        assertEquals("애월동물병원", response.getName());
        assertEquals("제주시 애월읍 123", response.getAddress());
        assertEquals("064-000-0000", response.getPhone());
        assertEquals(new BigDecimal("33.4500000"), response.getLatitude());
        assertTrue(response.getIs24h());
        assertFalse(response.getIsHolidayOpen());
        assertEquals(15, response.getAvgWaitMinutes());
        assertEquals(LocalDateTime.of(2026, 8, 1, 10, 30), response.getUpdatedAt());
    }

    @Test
    @DisplayName("is_holiday_open이 NULL(정보 미확인)이면 false로 강제 변환하지 않고 null을 유지한다")
    void should_mapIsHolidayOpenToNull_when_infoUnknown() {
        Map<String, Object> row = hospitalDetailRow();
        row.put("is_holiday_open", null);
        when(emergencyMapper.findById(1L)).thenReturn(row);

        HospitalDetailResponse response = emergencyService.getDetail(1L);

        assertNull(response.getIsHolidayOpen());
    }

    @Test
    @DisplayName("존재하지 않는 hospitalId 조회 시 404 BusinessException을 던진다")
    void should_throwNotFound_when_hospitalIdDoesNotExist() {
        when(emergencyMapper.findById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> emergencyService.getDetail(999L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    @DisplayName("hospitalId가 0이면 400 BusinessException을 던지고 mapper를 호출하지 않는다")
    void should_throwBadRequest_when_hospitalIdIsZero() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> emergencyService.getDetail(0L));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(emergencyMapper);
    }

    @Test
    @DisplayName("hospitalId가 음수이면 400 BusinessException을 던지고 mapper를 호출하지 않는다")
    void should_throwBadRequest_when_hospitalIdIsNegative() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> emergencyService.getDetail(-1L));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(emergencyMapper);
    }

    @Test
    @DisplayName("hospitalId가 null이면 400 BusinessException을 던지고 mapper를 호출하지 않는다")
    void should_throwBadRequest_when_hospitalIdIsNull() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> emergencyService.getDetail(null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(emergencyMapper);
    }
}
