package com.aewol.domain.emergency.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface EmergencyMapper {
    List<Map<String, Object>> findNearby(@Param("lat") double lat, @Param("lng") double lng, @Param("radiusKm") double radiusKm);
    List<Map<String, Object>> find24hHospitals();
}
