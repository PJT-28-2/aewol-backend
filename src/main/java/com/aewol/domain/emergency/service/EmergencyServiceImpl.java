package com.aewol.domain.emergency.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.emergency.dto.HospitalDetailResponse;
import com.aewol.domain.emergency.dto.HospitalResponse;
import com.aewol.domain.emergency.mapper.EmergencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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

    @Override
    public HospitalDetailResponse getDetail(Long hospitalId) {
        if (hospitalId == null || hospitalId < 1) {
            throw new BusinessException("hospitalId는 1 이상이어야 합니다.");
        }
        Map<String, Object> row = emergencyMapper.findById(hospitalId);
        if (row == null) {
            throw BusinessException.notFound("존재하지 않는 병원입니다.");
        }
        return toDetailResponse(row);
    }

    private HospitalDetailResponse toDetailResponse(Map<String, Object> row) {
        return HospitalDetailResponse.builder()
                .hospitalId(toLong(row.get("hospital_id")))
                .name((String) row.get("name"))
                .address((String) row.get("address"))
                .phone((String) row.get("phone"))
                .latitude(toBigDecimal(row.get("latitude")))
                .longitude(toBigDecimal(row.get("longitude")))
                .is24h(toBoolean(row.get("is_24h")))
                .isHolidayOpen(toNullableBoolean(row.get("is_holiday_open")))
                .avgWaitMinutes(toInteger(row.get("avg_wait_minutes")))
                .updatedAt(toLocalDateTime(row.get("updated_at")))
                .build();
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

    private static boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static Boolean toNullableBoolean(Object value) {
        if (value == null) {
            return null;
        }
        return toBoolean(value);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.valueOf(value.toString());
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString());
    }
}
