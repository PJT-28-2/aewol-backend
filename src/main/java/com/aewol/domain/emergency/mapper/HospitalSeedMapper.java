package com.aewol.domain.emergency.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/**
 * 병원 데이터 시딩(공공데이터 upsert) 전용 매퍼.
 * 조회용 API는 {@link EmergencyMapper}가 담당한다.
 */
@Mapper
public interface HospitalSeedMapper {

    int upsertHospital(Map<String, Object> hospital);
}
