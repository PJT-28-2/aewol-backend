package com.aewol.domain.emergency.service;

import com.aewol.domain.emergency.mapper.HospitalSeedMapper;
import com.aewol.external.animalhospital.AnimalHospitalClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalSeedServiceImpl implements HospitalSeedService {

    /** 영업상태명에 이 문구가 포함되면 시딩하지 않는다 (검증 필요 — 실제 값 확인 전 가정). */
    private static final String CLOSED_STATUS_MARKER = "폐업";

    private final AnimalHospitalClient animalHospitalClient;
    private final HospitalSeedMapper hospitalSeedMapper;
    private final HospitalSeedLock hospitalSeedLock;
    private final TransactionOperations transactionOperations;

    @Override
    public int syncHospitals() {
        if (!animalHospitalClient.isConfigured()) {
            log.warn("animal-hospital service-key 미설정 — 시딩을 건너뜁니다.");
            return 0;
        }

        return hospitalSeedLock.execute(this::syncConfiguredHospitals);
    }

    private int syncConfiguredHospitals() {
        List<Map<String, Object>> rows = collectRows();
        Integer upserted = transactionOperations.execute(status -> persistRows(rows));
        return Objects.requireNonNull(upserted, "병원 시딩 트랜잭션 결과가 없습니다.");
    }

    /** 외부 HTTP 호출은 DB 트랜잭션을 열기 전에 모두 완료한다. */
    private List<Map<String, Object>> collectRows() {
        List<Map<String, Object>> rows = animalHospitalClient.findAllHospitals();
        log.info("[HospitalSeed] 동물병원 원본 {}건 수집", rows.size());
        return rows;
    }

    private int persistRows(List<Map<String, Object>> rows) {
        int upserted = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            if (persistOne(row)) {
                upserted++;
            } else {
                skipped++;
            }
        }
        log.info("[HospitalSeed] upsert {}건, skip {}건", upserted, skipped);
        return upserted;
    }

    private boolean persistOne(Map<String, Object> row) {
        String mgtNo = animalHospitalClient.mgtNo(row);
        if (mgtNo == null) {
            log.warn("[HospitalSeed] 관리번호(mgtNo) 없는 행 스킵 - row={}", row);
            return false;
        }

        String statusName = animalHospitalClient.statusName(row);
        if (statusName != null && statusName.contains(CLOSED_STATUS_MARKER)) {
            return false;
        }

        String name = animalHospitalClient.name(row);
        String address = animalHospitalClient.address(row);
        if (name == null || address == null) {
            log.warn("[HospitalSeed] 병원명/주소 없는 행 스킵 - mgtNo={}", mgtNo);
            return false;
        }

        double[] wgs84 = animalHospitalClient.toWgs84(row);

        Map<String, Object> hospital = new HashMap<>();
        hospital.put("externalMngNo", mgtNo);
        hospital.put("name", name);
        hospital.put("address", address);
        hospital.put("phone", animalHospitalClient.phone(row));
        hospital.put("latitude", wgs84 == null ? null : BigDecimal.valueOf(wgs84[1]));
        hospital.put("longitude", wgs84 == null ? null : BigDecimal.valueOf(wgs84[0]));
        // 공공데이터가 제공하지 않는 필드의 기본값: is_24h는 NOT NULL 컬럼이라 0(false),
        // is_holiday_open/avg_wait_minutes는 nullable 컬럼이라 "정보 미확인" 의미로 NULL.
        hospital.put("is24h", 0);
        hospital.put("isHolidayOpen", null);
        hospital.put("avgWaitMinutes", null);

        hospitalSeedMapper.upsertHospital(hospital);
        return true;
    }
}
