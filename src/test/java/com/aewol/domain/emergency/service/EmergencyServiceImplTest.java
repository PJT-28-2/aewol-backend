package com.aewol.domain.emergency.service;

import com.aewol.domain.emergency.dto.HospitalResponse;
import com.aewol.domain.emergency.mapper.EmergencyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
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
}
