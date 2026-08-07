package com.aewol.domain.emergency.service;

import com.aewol.domain.emergency.mapper.HospitalSeedMapper;
import com.aewol.external.animalhospital.AnimalHospitalClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalSeedServiceImpl implements HospitalSeedService {

    /**
     * 영업상태명에 이 문구가 포함되면 폐업으로 간주해 upsert 대신 삭제한다.
     * (검증 필요 — 라이브 샘플에서 실제 "폐업" 값을 아직 확인하지 못했다. 반드시 마커 문구가
     * 정확히 이 값이어야 한다는 보장은 없으므로, 실데이터에서 폐업 건을 확인하면 재검증 필요.)
     */
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

    private int syncConfiguredHospitals(BooleanSupplier lockOwned) {
        List<Map<String, Object>> rows = collectRows();
        // 수집(HTTP)에 오래 걸려 그 사이 락 소유권을 잃었을 수 있으니, DB 트랜잭션을 열기
        // 전에 한 번 더 확인한다. 트랜잭션 안에서도 행마다 다시 확인한다(persistRows 참고).
        requireStillOwned(lockOwned);
        Integer upserted = transactionOperations.execute(status -> persistRows(rows, lockOwned));
        return Objects.requireNonNull(upserted, "병원 시딩 트랜잭션 결과가 없습니다.");
    }

    /** 외부 HTTP 호출은 DB 트랜잭션을 열기 전에 모두 완료한다. */
    private List<Map<String, Object>> collectRows() {
        List<Map<String, Object>> rows = animalHospitalClient.findAllHospitals();
        log.info("[HospitalSeed] 동물병원 원본 {}건 수집", rows.size());
        return rows;
    }

    private int persistRows(List<Map<String, Object>> rows, BooleanSupplier lockOwned) {
        int upserted = 0;
        int deleted = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            // 인메모리 플래그 조회라 Redis 왕복 없이 행마다 확인해도 비용이 거의 없다.
            // 소유권을 잃었으면 즉시 예외를 던져 트랜잭션 전체를 롤백시킨다 — 이전 실행의
            // 오래된 데이터가 새 실행이 이미 반영한 폐업/갱신을 덮어쓰는 것을 막기 위함이다.
            requireStillOwned(lockOwned);
            switch (persistOne(row)) {
                case UPSERTED -> upserted++;
                case DELETED -> deleted++;
                case SKIPPED -> skipped++;
            }
        }
        log.info("[HospitalSeed] upsert {}건, 폐업 삭제 {}건, skip {}건", upserted, deleted, skipped);
        return upserted;
    }

    private void requireStillOwned(BooleanSupplier lockOwned) {
        if (!lockOwned.getAsBoolean()) {
            throw new IllegalStateException("병원 시딩 잠금 소유권을 상실해 DB 쓰기를 중단합니다.");
        }
    }

    /**
     * 폐업으로 전환된 병원은 upsert하지 않고 삭제한다. 이전 실행에서 이미 활성 상태로
     * 저장된 레코드가 있다면(재실행 시 재조회되어 계속 노출되는 것을) 없애기 위함이며,
     * 애초에 저장된 적 없는 폐업 병원에 대해서도 안전하게(영향 행 0건) 호출된다.
     */
    private Outcome persistOne(Map<String, Object> row) {
        String mgtNo = animalHospitalClient.mgtNo(row);
        if (mgtNo == null) {
            log.warn("[HospitalSeed] 관리번호(mgtNo) 없는 행 스킵 - row={}", row);
            return Outcome.SKIPPED;
        }

        String statusName = animalHospitalClient.statusName(row);
        if (statusName != null && statusName.contains(CLOSED_STATUS_MARKER)) {
            hospitalSeedMapper.deleteHospitalByExternalMngNo(mgtNo);
            return Outcome.DELETED;
        }

        String name = animalHospitalClient.name(row);
        String address = animalHospitalClient.address(row);
        if (name == null || address == null) {
            log.warn("[HospitalSeed] 병원명/주소 없는 행 스킵 - mgtNo={}", mgtNo);
            return Outcome.SKIPPED;
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
        return Outcome.UPSERTED;
    }

    private enum Outcome { UPSERTED, DELETED, SKIPPED }
}
