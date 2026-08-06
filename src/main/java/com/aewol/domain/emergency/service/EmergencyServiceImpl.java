package com.aewol.domain.emergency.service;

import com.aewol.domain.emergency.dto.HospitalResponse;
import com.aewol.domain.emergency.mapper.EmergencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmergencyServiceImpl implements EmergencyService {

    private final EmergencyMapper emergencyMapper;

    @Override
    public List<HospitalResponse> searchNearby(double latitude, double longitude, double radiusKm, boolean is24h) {
        return emergencyMapper.findNearby(latitude, longitude, radiusKm, is24h).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private HospitalResponse toResponse(Map<String, Object> row) {
        return HospitalResponse.builder()
                .name((String) row.get("name"))
                .address((String) row.get("address"))
                .phone((String) row.get("phone"))
                .latitude(toBigDecimal(row.get("latitude")))
                .longitude(toBigDecimal(row.get("longitude")))
                .distanceKm(toBigDecimal(row.get("distance_km")))
                .build();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(value.toString());
    }
}
